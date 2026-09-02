// =====================================================
// qwen_forward.cpp — 自写 forward pass, 纯数学, 零 ggml 依赖
//
// 背景: v1.3.24 forward 用 ggml 图, 有 5 个致命 bug (结构体偏移错,
//   data=NULL, attention 全跳过, KV cache 没写, static 残留).
//   本次重写: 所有算子自己写, 每一步都是公式, 透明可控.
//
// 支持: Qwen2.5 1.5B / 3B 等 decoder-only transformer,
//   从 QwenModelConfig 读超参数, 一套代码通吃.
// =====================================================

#include "qwen_infer.h"

#include <cmath>
#include <cstring>
#include <cstdio>
#include <vector>
#include <algorithm>
#include <random>
#include <string>

// ---------- logging ----------
#define FWD_LOG(...) do { if (g_fwd_log_cb) { char _b[256]; snprintf(_b,sizeof(_b),__VA_ARGS__); g_fwd_log_cb(g_fwd_log_ud,_b); } } while(0)
static qwen_cb_log g_fwd_log_cb = nullptr;
static void *      g_fwd_log_ud = nullptr;

void fwd_set_log_cb(qwen_cb_log cb, void * ud) {
    g_fwd_log_cb = cb; g_fwd_log_ud = ud;
}

// ---------- fp16 helpers (和 qwen_infer.cpp 重复但独立, 解耦) ----------
static inline uint16_t f32_to_f16(float f) {
    union { float f; uint32_t u; } u; u.f = f;
    uint32_t sign = u.u >> 31;
    int32_t  exp  = ((u.u >> 23) & 0xFF) - 127 + 15;
    uint32_t frac = (u.u & 0x007FFFFF) >> 13;
    if (exp <= 0) return (sign << 15);
    if (exp >= 31) exp = 31;
    return (uint16_t)((sign << 15) | (exp << 10) | (frac & 0x3FF));
}
static inline float f16_to_f32(uint16_t h) {
    union { uint32_t u; float f; } u;
    uint32_t sign = h >> 15;
    int32_t  exp  = (h >> 10) & 0x1F;
    uint32_t frac = h & 0x3FF;
    if (exp == 0 && frac == 0) { u.u = (sign << 31); return u.f; }
    if (exp == 31) { u.u = (sign << 31) | 0x7F800000; return u.f; }
    exp = exp - 15 + 127;
    u.u = (sign << 31) | (exp << 23) | (frac << 13);
    return u.f;
}

// =====================================================
// 1. Q4_K_M 反量化 (严格照搬 llama.cpp b5180 dequantize_row_q4_K)
//
// block_q4_K 结构 (256 元素 = 144 字节):
//   off 0:  ggml_half d       (2B, super-block scale, fp16)
//   off 2:  ggml_half dmin    (2B, super-block min scale, fp16)
//   off 4:  uint8 scales[12]  (12B, 6-bit 量化的 scale/min 对)
//   off 16: uint8 qs[128]     (128B, 4bit 数据, 高低 nibble)
//   总计: 144 bytes
//
// 反量化公式:
//   每 64 元素一组, 用 get_scale_min_k4 从 scales[12] 解出 2 组 (sc, m)
//   d1 = d * sc; m1 = min * m     (sc/m 是 6-bit 整数 0..63)
//   前 32: y[l] = d1 * (q[l] & 0xF) - m1
//   后 32: y[l] = d2 * (q[l] >> 4)  - m2
// =====================================================

// fp16 → fp32 (和上面 f16_to_f32 重复, 这里只用于 d/dmin)
static inline float fp16_to_fp32_raw(uint16_t h) {
    union { uint32_t u; float f; } u;
    uint32_t sign = h >> 15;
    int32_t  exp  = (h >> 10) & 0x1F;
    uint32_t frac = h & 0x3FF;
    if (exp == 0 && frac == 0) { u.u = (sign << 31); return u.f; }
    if (exp == 31) { u.u = (sign << 31) | 0x7F800000; return u.f; }
    exp = exp - 15 + 127;
    u.u = (sign << 31) | (exp << 23) | (frac << 13);
    return u.f;
}

// get_scale_min_k4: 从 12 字节 scales 解出 6-bit sc 和 m
// 对应 llama.cpp get_scale_min_k4()
static inline void get_scale_min_k4(int j, const uint8_t * q, uint8_t * d, uint8_t * m) {
    if (j < 4) {
        *d = q[j] & 63; *m = q[j + 4] & 63;
    } else {
        *d = (q[j+4] & 0xF) | ((q[j-4] >> 6) << 4);
        *m = (q[j+4] >>  4) | ((q[j-0] >> 6) << 4);
    }
}

// 一个 block (256 元素 = 144 字节) 反量化
static void dequant_q4km_block(float * out, const uint8_t * blk) {
    uint16_t d_raw, dmin_raw;
    memcpy(&d_raw,   blk + 0, 2);
    memcpy(&dmin_raw, blk + 2, 2);
    const float d   = fp16_to_fp32_raw(d_raw);
    const float min = fp16_to_fp32_raw(dmin_raw);
    const uint8_t * scales = blk + 4;
    const uint8_t * q = blk + 16;

    int is = 0;
    uint8_t sc, m;
    for (int j = 0; j < 256; j += 64) {
        get_scale_min_k4(is + 0, scales, &sc, &m);
        const float d1 = d * sc; const float m1 = min * m;
        get_scale_min_k4(is + 1, scales, &sc, &m);
        const float d2 = d * sc; const float m2 = min * m;
        for (int l = 0; l < 32; ++l) *out++ = d1 * (q[l] & 0xF) - m1;
        for (int l = 0; l < 32; ++l) *out++ = d2 * (q[l] >> 4)  - m2;
        q += 32; is += 2;
    }
}

// 反量化整个 Q4_K_M 张量到 F32 buffer
// QK_K=256 元素一组, 每 block 144 字节
// nb1 来自 GGUF (ggml_loader 已正确读取)
static void dequant_q4km_tensor(float * out, const uint8_t * data,
                                int64_t ne0, int64_t ne1, size_t nb1) {
    static const int BLOCK_SIZE_BYTES = 144;  // sizeof(block_q4_K) = 2+2+12+128
    static const int BLOCK_NELEM     = 256;  // QK_K

    int64_t nb0_blocks = (ne0 + BLOCK_NELEM - 1) / BLOCK_NELEM;
    for (int64_t r = 0; r < ne1; ++r) {
        const uint8_t * row = data + r * nb1;
        for (int64_t b = 0; b < nb0_blocks; ++b) {
            const uint8_t * blk = row + b * BLOCK_SIZE_BYTES;
            float * dst = out + r * ne0 + b * BLOCK_NELEM;
            int n_elem = (int)std::min<int64_t>(BLOCK_NELEM, ne0 - b * BLOCK_NELEM);
            float tmp[BLOCK_NELEM];
            dequant_q4km_block(tmp, blk);
            for (int i = 0; i < n_elem; ++i) dst[i] = tmp[i];
        }
    }
}

// F16 张量直接解引用为 float 数组
static void copy_f16_to_f32(float * out, const uint16_t * src, int64_t ne0, int64_t ne1, size_t nb1) {
    for (int64_t r = 0; r < ne1; ++r) {
        const uint16_t * row = src + (r * nb1) / sizeof(uint16_t);
        for (int64_t c = 0; c < ne0; ++c) {
            out[r * ne0 + c] = f16_to_f32(row[c]);
        }
    }
}

// F32 张量直接 memcpy
static void copy_f32(float * out, const float * src, int64_t ne0, int64_t ne1, size_t nb1) {
    for (int64_t r = 0; r < ne1; ++r) {
        const float * row = src + (r * nb1) / sizeof(float);
        memcpy(out + r * ne0, row, ne0 * sizeof(float));
    }
}

// 把 QwenTensor 反量化成 F32 buffer
// 返回: malloc 出来的 float*, 调用者 free
// 不修改 model 的 mmap 数据
static float * dequant_tensor(const QwenTensor * t, size_t & out_ne0, size_t & out_ne1) {
    if (!t || !t->data) return nullptr;
    out_ne0 = t->ne[0];
    out_ne1 = t->ne[1] > 0 ? t->ne[1] : 1;
    int64_t total = (int64_t)out_ne0 * (int64_t)out_ne1;
    if (total <= 0) return nullptr;
    float * buf = (float *)malloc(total * sizeof(float));
    if (!buf) return nullptr;

    if (t->type == 13 /*GGML_TYPE_Q4_K_M*/) {
        dequant_q4km_tensor(buf, (const uint8_t *)t->data, (int64_t)out_ne0, (int64_t)out_ne1, t->nb[1]);
    } else if (t->type == 1 /*GGML_TYPE_F16*/) {
        copy_f16_to_f32(buf, (const uint16_t *)t->data, (int64_t)out_ne0, (int64_t)out_ne1, t->nb[1]);
    } else if (t->type == 0 /*GGML_TYPE_F32*/) {
        copy_f32(buf, (const float *)t->data, (int64_t)out_ne0, (int64_t)out_ne1, t->nb[1]);
    } else {
        // 其他量化类型: 简单 fallback, 只把 F16/F32 行拷贝
        FWD_LOG("WARN: dequant_tensor type=%d not fully supported, trying F16", t->type);
        copy_f16_to_f32(buf, (const uint16_t *)t->data, (int64_t)out_ne0, (int64_t)out_ne1, t->nb[1]);
    }
    return buf;
}

// =====================================================
// 2. RMSNorm (PLAN.md 九/RMSNorm)
//    out[i] = x[i] / sqrt(mean(x^2) + eps) * w[i]
// =====================================================
static void rms_norm(float * out, const float * x, const float * w, int n, float eps) {
    float sum_sq = 0.0f;
    for (int i = 0; i < n; ++i) sum_sq += x[i] * x[i];
    float rms_inv = 1.0f / sqrtf(sum_sq / (float)n + eps);
    for (int i = 0; i < n; ++i) out[i] = x[i] * rms_inv * w[i];
}

// =====================================================
// 3. SiLU
//    out[i] = x[i] * sigmoid(x[i])
// =====================================================
static void silu(float * out, const float * x, int n) {
    for (int i = 0; i < n; ++i) {
        float v = x[i];
        out[i] = v / (1.0f + expf(-v));
    }
}

// =====================================================
// 4. Matmul (naive O(N^3), 先跑通, 后面加 NEON)
//    C[m × n] = A[m × k] @ B[k × n]
//    A, B, C 都是行主序 F32 buffer
// =====================================================
static void matmul(float * C, const float * A, const float * B,
                   int m, int k, int n) {
    // 清零
    for (int i = 0; i < m * n; ++i) C[i] = 0.0f;
    for (int i = 0; i < m; ++i) {
        const float * Ai = A + i * k;
        float * Ci = C + i * n;
        for (int p = 0; p < k; ++p) {
            float aip = Ai[p];
            const float * Bp = B + p * n;
            for (int j = 0; j < n; ++j) {
                Ci[j] += aip * Bp[j];
            }
        }
    }
}

// 向量-矩阵乘 (x @ W, 更常见: x[n_embd] @ W[n_embd, n_out] → out[n_out])
static void vec_mat(float * out, const float * x, const float * W,
                    int n_embd, int n_out) {
    // out[j] = sum_i x[i] * W[i*n_out + j]
    for (int j = 0; j < n_out; ++j) {
        float acc = 0.0f;
        for (int i = 0; i < n_embd; ++i) {
            acc += x[i] * W[i * n_out + j];
        }
        out[j] = acc;
    }
}

// =====================================================
// 5. RoPE NeoX 风格 (PLAN.md 九/RoPE NeoX)
//    对 q 和 k 的每个 head 的 head_dim 维做旋转
//    inv_freq[i] = 1.0 / (freq_base^(2i/head_dim))   i = 0..head_dim/2-1
// =====================================================
static void rope_head(float * head_data, int head_dim, int pos, float freq_base,
                      float * cos_buf, float * sin_buf) {
    // head_data: [head_dim], 原地修改
    int half = head_dim / 2;
    for (int i = 0; i < half; ++i) {
        float c = cos_buf[i];
        float s = sin_buf[i];
        float d0 = head_data[2*i];
        float d1 = head_data[2*i + 1];
        head_data[2*i]     = d0 * c - d1 * s;
        head_data[2*i + 1] = d0 * s + d1 * c;
    }
}

static void compute_rope_cache(float * cos_buf, float * sin_buf,
                                int head_dim, int pos, float freq_base) {
    int half = head_dim / 2;
    for (int i = 0; i < half; ++i) {
        // inv_freq = 1.0 / base^(2i/head_dim)  → exp(-2i * log(base) / head_dim)
        float inv_freq = expf(-2.0f * (float)i * logf(freq_base) / (float)head_dim);
        float angle = (float)pos * inv_freq;
        cos_buf[i] = cosf(angle);
        sin_buf[i] = sinf(angle);
    }
}

// =====================================================
// 6. GQA Attention (PLAN.md 九/GQA Attention)
//
// Q: [n_head, head_dim]  — 每个 head 一行
// KV_cache: [n_layer][n_head_kv, max_seq, head_dim] 存 FP16
// pos: 当前位置 (0-based)
// out: [n_head, head_dim]
// =====================================================
static void attention_gqa(
        float * out,
        const float * Q,       // [n_head, head_dim]
        const uint16_t * k_cache_layer,  // [max_seq, n_head_kv, head_dim] FP16
        const uint16_t * v_cache_layer,  // 同上
        int pos, int n_head, int n_head_kv, int head_dim,
        int max_seq)
{
    int rep = n_head / n_head_kv;
    int n_ctx = pos + 1;
    float inv_sqrt_d = 1.0f / sqrtf((float)head_dim);

    // 临时 buffer: scores[n_ctx]
    std::vector<float> scores(n_ctx);
    std::vector<float> exp_scores(n_ctx);

    for (int h = 0; h < n_head; ++h) {
        int kv_h = h / rep;
        const float * q_h = Q + h * head_dim;

        // QK^T: 对每个历史位置, 取 K[kv_h, t], 点积 q_h
        for (int t = 0; t < n_ctx; ++t) {
            float dot = 0.0f;
            const uint16_t * k_row = k_cache_layer + (t * n_head_kv + kv_h) * head_dim;
            for (int d = 0; d < head_dim; ++d) {
                dot += q_h[d] * f16_to_f32(k_row[d]);
            }
            scores[t] = dot * inv_sqrt_d;
        }

        // softmax
        float max_s = *std::max_element(scores.begin(), scores.begin() + n_ctx);
        float sum_e = 0.0f;
        for (int t = 0; t < n_ctx; ++t) {
            float e = expf(scores[t] - max_s);
            exp_scores[t] = e;
            sum_e += e;
        }
        for (int t = 0; t < n_ctx; ++t) scores[t] = exp_scores[t] / sum_e;

        // out[h] = V[kv_h] @ scores
        float * out_h = out + h * head_dim;
        for (int d = 0; d < head_dim; ++d) {
            float acc = 0.0f;
            for (int t = 0; t < n_ctx; ++t) {
                const uint16_t * v_row = v_cache_layer + (t * n_head_kv + kv_h) * head_dim;
                acc += scores[t] * f16_to_f32(v_row[d]);
            }
            out_h[d] = acc;
        }
    }
}

// =====================================================
// 7. SwiGLU (PLAN.md 九/Qwen2 SwiGLU)
//    gate = X @ W_gate   [n_embd] @ [n_embd, n_ff] → [n_ff]
//    up   = X @ W_up
//    hidden = silu(gate) ⊙ up
//    out = hidden @ W_down   [n_ff] @ [n_ff, n_embd] → [n_embd]
// =====================================================
static void swiglu(float * out, const float * x,
                   const float * w_gate, const float * w_up, const float * w_down,
                   int n_embd, int n_ff) {
    std::vector<float> gate(n_ff), up(n_ff);
    vec_mat(gate.data(), x, w_gate, n_embd, n_ff);
    vec_mat(up.data(),   x, w_up,   n_embd, n_ff);
    // silu(gate) * up
    for (int i = 0; i < n_ff; ++i) {
        float g = gate[i];
        gate[i] = g / (1.0f + expf(-g)) * up[i];  // silu 内联
    }
    vec_mat(out, gate.data(), w_down, n_ff, n_embd);
}

// =====================================================
// 8. 写 KV cache (把当前 token 的 K,V 存进 FP16 cache)
// =====================================================
static void write_kv_cache(uint16_t * k_cache_layer, uint16_t * v_cache_layer,
                           const float * K, const float * V,
                           int pos, int n_head_kv, int head_dim) {
    for (int h = 0; h < n_head_kv; ++h) {
        const float * k_h = K + h * head_dim;
        const float * v_h = V + h * head_dim;
        uint16_t * k_row = k_cache_layer + (pos * n_head_kv + h) * head_dim;
        uint16_t * v_row = v_cache_layer + (pos * n_head_kv + h) * head_dim;
        for (int d = 0; d < head_dim; ++d) {
            k_row[d] = f32_to_f16(k_h[d]);
            v_row[d] = f32_to_f16(v_h[d]);
        }
    }
}

// =====================================================
// 9. 完整 Transformer 层 forward
//
// x: [n_embd] 输入输出 (原地)
// layer_idx: 当前层号
// pos: 当前 token 位置
// =====================================================
static void transformer_layer(
        float * x, int layer_idx, int pos,
        QwenModel * m,
        uint16_t * k_cache_layer, uint16_t * v_cache_layer)
{
    auto & c = m->cfg;
    int n_embd  = c.n_embd;
    int n_head  = c.n_head;
    int n_head_kv = c.n_head_kv;
    int head_dim = c.head_dim;
    int n_ff    = c.n_ff;

    // --- 权重 ---
    char prefix[32];
    snprintf(prefix, sizeof(prefix), "blk.%d.", layer_idx);
    std::string pfx = prefix;

    auto * w_attn_norm = m->find_tensor(pfx + "attn_norm.weight");
    auto * w_attn_q    = m->find_tensor(pfx + "attn_q.weight");
    auto * w_attn_k    = m->find_tensor(pfx + "attn_k.weight");
    auto * w_attn_v    = m->find_tensor(pfx + "attn_v.weight");
    auto * w_attn_o    = m->find_tensor(pfx + "attn_output.weight");
    auto * w_ffn_norm  = m->find_tensor(pfx + "ffn_norm.weight");
    auto * w_gate      = m->find_tensor(pfx + "ffn_gate.weight");
    auto * w_up        = m->find_tensor(pfx + "ffn_up.weight");
    auto * w_down      = m->find_tensor(pfx + "ffn_down.weight");

    if (!w_attn_norm || !w_attn_q || !w_attn_k || !w_attn_v || !w_attn_o ||
        !w_ffn_norm  || !w_gate   || !w_up     || !w_down) {
        FWD_LOG("ERROR: missing weight in layer %d", layer_idx);
        return;
    }

    // --- Attention ---
    std::vector<float> norm_x(n_embd);
    rms_norm(norm_x.data(), x,
             (const float *)w_attn_norm->data, n_embd, c.rms_norm_eps);

    // Q, K, V 投影: [n_embd] @ [n_embd, head_dim*n_head_kv_or_n_head]
    std::vector<float> q_proj(head_dim * n_head);       // [1536]
    std::vector<float> k_proj(head_dim * n_head_kv);    // [256]
    std::vector<float> v_proj(head_dim * n_head_kv);    // [256]

    // w_attn_q: [n_embd, head_dim*n_head], 先反量化
    size_t q_ne0, q_ne1;
    float * Wq = dequant_tensor(w_attn_q, q_ne0, q_ne1);
    float * Wk = dequant_tensor(w_attn_k, q_ne0, q_ne1);
    float * Wv = dequant_tensor(w_attn_v, q_ne0, q_ne1);
    float * Wo = dequant_tensor(w_attn_o, q_ne0, q_ne1);
    float * Wgn = dequant_tensor(w_gate, q_ne0, q_ne1);
    float * Wun = dequant_tensor(w_up,   q_ne0, q_ne1);
    float * Wdn = dequant_tensor(w_down, q_ne0, q_ne1);

    if (!Wq || !Wk || !Wv || !Wo || !Wgn || !Wun || !Wdn) {
        FWD_LOG("ERROR: dequant failed layer %d", layer_idx);
        free(Wq); free(Wk); free(Wv); free(Wo); free(Wgn); free(Wun); free(Wdn);
        return;
    }

    // vec_mat: out[n_out] = norm_x[n_embd] @ W[n_embd, n_out]
    vec_mat(q_proj.data(), norm_x.data(), Wq, n_embd, head_dim * n_head);
    vec_mat(k_proj.data(), norm_x.data(), Wk, n_embd, head_dim * n_head_kv);
    vec_mat(v_proj.data(), norm_x.data(), Wv, n_embd, head_dim * n_head_kv);

    // RoPE: 对每个 head 的 [head_dim] 做旋转
    std::vector<float> cos_buf(head_dim / 2), sin_buf(head_dim / 2);
    compute_rope_cache(cos_buf.data(), sin_buf.data(), head_dim, pos, c.rope_freq_base);
    for (int h = 0; h < n_head; ++h) {
        rope_head(q_proj.data() + h * head_dim, head_dim, pos, c.rope_freq_base,
                  cos_buf.data(), sin_buf.data());
    }
    for (int h = 0; h < n_head_kv; ++h) {
        rope_head(k_proj.data() + h * head_dim, head_dim, pos, c.rope_freq_base,
                  cos_buf.data(), sin_buf.data());
    }

    // 写 KV cache
    write_kv_cache(k_cache_layer, v_cache_layer,
                   k_proj.data(), v_proj.data(),
                   pos, n_head_kv, head_dim);

    // Attention: QK^T softmax V
    std::vector<float> attn_out(head_dim * n_head);
    attention_gqa(attn_out.data(), q_proj.data(),
                  k_cache_layer, v_cache_layer,
                  pos, n_head, n_head_kv, head_dim, c.max_seq_len);

    // O 投影: [head_dim*n_head] @ [head_dim*n_head, n_embd] → [n_embd]
    std::vector<float> o_proj_out(n_embd);
    vec_mat(o_proj_out.data(), attn_out.data(), Wo, head_dim * n_head, n_embd);

    // Residual
    for (int i = 0; i < n_embd; ++i) x[i] += o_proj_out[i];

    // --- FFN (SwiGLU) ---
    std::vector<float> norm_x2(n_embd);
    float * Wfn = dequant_tensor(w_ffn_norm, q_ne0, q_ne1);
    if (Wfn) {
        rms_norm(norm_x2.data(), x, Wfn, n_embd, c.rms_norm_eps);
        free(Wfn);
    } else {
        // fallback: F16
        rms_norm(norm_x2.data(), x, (const float *)w_ffn_norm->data, n_embd, c.rms_norm_eps);
    }

    std::vector<float> ffn_out(n_embd);
    swiglu(ffn_out.data(), norm_x2.data(), Wgn, Wun, Wdn, n_embd, n_ff);

    for (int i = 0; i < n_embd; ++i) x[i] += ffn_out[i];

    // --- 释放 ---
    free(Wq); free(Wk); free(Wv); free(Wo);
    free(Wgn); free(Wun); free(Wdn);
}

// =====================================================
// 10. 顶层 forward_step
//     输入: token_id + pos
//     输出: logits (vocab_size 个 F32)
// =====================================================
bool fwd_forward_step(QwenSession * sess, QwenModel * model,
                      int32_t token_id, int32_t pos,
                      float * logits_out)
{
    if (!sess || !model || !logits_out) return false;
    auto & c = model->cfg;

    // Embedding: token_embd.weight[token_id], dequant 一行
    auto * W_embd = model->find_tensor("token_embd.weight");
    if (!W_embd) { FWD_LOG("ERROR: token_embd.weight not found"); return false; }

    size_t q_ne0, q_ne1;
    float * emb = dequant_tensor(W_embd, q_ne0, q_ne1);
    if (!emb) { FWD_LOG("ERROR: dequant emb"); return false; }
    // emb 是 [vocab_size, n_embd], 取第 token_id 行
    float * x = emb + token_id * c.n_embd;
    // 深拷贝一份, 因为 emb 后面要 free
    std::vector<float> x_buf(c.n_embd);
    memcpy(x_buf.data(), x, c.n_embd * sizeof(float));
    free(emb);
    x = x_buf.data();

    // KV cache 指针: sess->k_cache[l] 和 sess->v_cache[l] 是 vector<uint16_t>
    // 每层存 [max_seq, n_head_kv, head_dim] FP16
    // stride: (pos * n_head_kv + head_kv) * head_dim 个 uint16_t

    for (int l = 0; l < c.n_layer; ++l) {
        uint16_t * k_layer = sess->k_cache[l].data();
        uint16_t * v_layer = sess->v_cache[l].data();
        transformer_layer(x, l, pos, model, k_layer, v_layer);
    }

    // Final RMSNorm
    auto * w_out_norm = model->find_tensor("output_norm.weight");
    if (!w_out_norm) { FWD_LOG("ERROR: output_norm.weight not found"); return false; }
    std::vector<float> final_norm(c.n_embd);
    float * Wfn = dequant_tensor(w_out_norm, q_ne0, q_ne1);
    if (Wfn) {
        rms_norm(final_norm.data(), x, Wfn, c.n_embd, c.rms_norm_eps);
        free(Wfn);
    } else {
        rms_norm(final_norm.data(), x, (const float *)w_out_norm->data, c.n_embd, c.rms_norm_eps);
    }

    // LM head: tie embeddings → 复用 token_embd.weight.T
    // lm_logits = final_norm @ token_embd.weight.T
    // token_embd.weight: [vocab_size, n_embd]
    // 所以 logits[i] = sum_j final_norm[j] * weight[i][j]
    for (int i = 0; i < c.vocab_size; ++i) logits_out[i] = 0.0f;

    float * emb2 = dequant_tensor(W_embd, q_ne0, q_ne1);
    if (emb2) {
        for (int i = 0; i < c.vocab_size; ++i) {
            float acc = 0.0f;
            const float * row = emb2 + i * c.n_embd;
            for (int j = 0; j < c.n_embd; ++j) {
                acc += final_norm[j] * row[j];
            }
            logits_out[i] = acc;
        }
        free(emb2);
    }
    return true;
}

// =====================================================
// 11. Sampling (简单版 top-k + top-p + temp)
// =====================================================
int fwd_sample(float * logits, int vocab_size,
               float temperature, float top_p, int top_k,
               uint32_t seed, int step)
{
    std::mt19937 rng(seed ^ (uint32_t)step);
    if (temperature <= 0.0f) temperature = 1e-3f;
    float invt = 1.0f / temperature;
    for (int i = 0; i < vocab_size; ++i) logits[i] *= invt;

    // top-k
    if (top_k > 0 && top_k < vocab_size) {
        std::vector<std::pair<float,int>> cand;
        cand.reserve(vocab_size);
        for (int i = 0; i < vocab_size; ++i) cand.emplace_back(logits[i], i);
        std::nth_element(cand.begin(), cand.begin() + top_k, cand.end(),
            [](auto &a, auto &b){ return a.first > b.first; });
        for (int i = top_k; i < vocab_size; ++i) logits[cand[i].second] = -1e30f;
    }

    // softmax
    float max_l = *std::max_element(logits, logits + vocab_size);
    float sum = 0.0f;
    for (int i = 0; i < vocab_size; ++i) {
        float e = expf(logits[i] - max_l);
        logits[i] = e;
        sum += e;
    }
    for (int i = 0; i < vocab_size; ++i) logits[i] /= sum;

    // top-p
    if (top_p > 0.0f && top_p < 1.0f) {
        std::vector<int> idx(vocab_size);
        for (int i = 0; i < vocab_size; ++i) idx[i] = i;
        std::sort(idx.begin(), idx.end(), [&](int a, int b){ return logits[a] > logits[b]; });
        float acc = 0; int stop = vocab_size;
        for (int i = 0; i < vocab_size; ++i) {
            acc += logits[idx[i]];
            if (acc >= top_p) { stop = i + 1; break; }
        }
        for (int i = stop; i < vocab_size; ++i) logits[idx[i]] = 0.0f;
        float s = 0;
        for (int i = 0; i < vocab_size; ++i) s += logits[i];
        if (s > 0) for (int i = 0; i < vocab_size; ++i) logits[i] /= s;
    }

    // categorical
    std::uniform_real_distribution<float> ud(0.0f, 1.0f);
    float r = ud(rng);
    float acc = 0;
    for (int i = 0; i < vocab_size; ++i) {
        acc += logits[i];
        if (acc >= r) return i;
    }
    return vocab_size - 1;
}

// =====================================================
// qwen_infer.cpp — v1.3.25 重写版
//
// 所有 forward 计算交给 qwen_forward.cpp (纯数学, 零 ggml 依赖).
// 本文件只保留: QwenSession 生命周期管理 + generate 循环 + JNI 入口.
// =====================================================

#include "qwen_infer.h"
#include "qwen_forward.h"  // 🆕 自写 forward 接口

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>
#include <algorithm>
#include <random>
#include <chrono>

// 🔴 v1.3.25-fix17: 时间戳工具（独立实现，不依赖 llama_jni 的 now_ms）
static inline int64_t qw_now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
}
#include <cinttypes>
#include <ctime>

// ---------- logging ----------
// 🔴 v1.3.25-fix18: 读 qwen_jni.cpp 的 g_cancel flag（第二次发消息真取消）
extern "C" bool qwen_should_cancel();
#define QW_CANCELLED() (qwen_should_cancel())
#define LOG(...) do { if (g_log_cb) { char _buf[256]; snprintf(_buf,sizeof(_buf),__VA_ARGS__); g_log_cb(g_log_ud,_buf); } } while(0)
static qwen_cb_log g_log_cb = nullptr;
static void * g_log_ud    = nullptr;

static char * err(const char * msg) {
    LOG("ERROR: %s", msg ? msg : "(null)");
    return msg ? strdup(msg) : strdup("unknown error");
}

// ---------- fp16 helpers (KV cache 用) ----------
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
    int32_t  exp  = ((h >> 10) & 0x1F);
    uint32_t frac = h & 0x3FF;
    if (exp == 0 && frac == 0) { u.u = (sign << 31); return u.f; }
    if (exp == 31) { u.u = (sign << 31) | 0x7F800000; return u.f; }
    exp = exp - 15 + 127;
    u.u = (sign << 31) | (exp << 23) | (frac << 13);
    return u.f;
}

// ---------- QwenSession 生命周期 ----------
QwenSession::~QwenSession() {
    k_cache.clear();
    v_cache.clear();
    model = nullptr;
}

bool QwenSession::init(QwenModel * m) {
    model = m;
    auto & c = m->cfg;
    // KV cache: 每层 [max_seq_len, n_head_kv, head_dim] FP16
    const size_t kv_per_layer = (size_t)c.max_seq_len * (size_t)c.n_head_kv * (size_t)c.head_dim;
    k_cache.assign(c.n_layer, std::vector<uint16_t>(kv_per_layer, 0));
    v_cache.assign(c.n_layer, std::vector<uint16_t>(kv_per_layer, 0));
    kv_pos = 0;
    return true;
}

// ---------- 公开 API ----------
char * qwen_generate(
    QwenModel            * model,
    const std::string    & prompt,
    int32_t                max_tokens,
    float                  temperature,
    float                  top_p,
    int32_t                top_k,
    uint32_t               seed,
    const QwenCallbacks  & cb)
{
    if (!model) return err("no model");
    g_log_cb = cb.log; g_log_ud = cb.ud;
    fwd_set_log_cb(cb.log, cb.ud);

    QwenSession sess;
    if (!sess.init(model)) return err("session init failed");

    if (cb.log) {
        auto dump = qwen_dump_model(model);
        cb.log(cb.ud, dump.c_str());
    }

    auto prompt_ids = qwen_encode_text(model, prompt, true);
    if (cb.log) {
        char buf[128];
        snprintf(buf, sizeof(buf), "prompt %zu tokens, max_gen=%d, seq_cap=%d",
                 prompt_ids.size(), max_tokens, model->cfg.max_seq_len);
        cb.log(cb.ud, buf);
    }
    if (seed == 0) seed = (uint32_t)time(nullptr);

    auto & c = model->cfg;
    std::vector<float> logits(c.vocab_size);
    std::string acc_utf8;
    const char * reason = "max_tokens";
    int32_t pos = 0;
    int32_t max_pos = c.max_seq_len - 1;

    // --- prompt forward: 逐个 token ---
    const size_t n_prompt = prompt_ids.size();
    int64_t t_pre0 = qw_now_ms();
    int report_step = 10;  // 每 10 token 报告一次进度（太频繁影响性能）
    if (n_prompt > 100) report_step = std::max<int>(1, (int)n_prompt / 50);
    for (size_t i = 0; i < prompt_ids.size(); ++i) {
        // 🔴 v1.3.25-fix18: 真取消。prefill 每个 token 检查一次 g_cancel flag。
        if (QW_CANCELLED()) {
            if (cb.done) cb.done(cb.ud, "cancel");
            return nullptr;
        }
        int32_t id = prompt_ids[i];
        if (!fwd_forward_step(&sess, model, id, pos, logits.data())) {
            return err("forward_step failed at prompt");
        }
        pos = sess.kv_pos = pos + 1;
        if (pos >= max_pos) { reason = "context_limit"; goto DONE; }

        // 🔴 v1.3.25-fix17: prompt prefill 进度日志（每 report_step tokens + 开始/结束）
        if (i == 0 || (i + 1) % report_step == 0 || i + 1 == n_prompt) {
            if (cb.log) {
                int64_t now = qw_now_ms();
                char buf[256];
                snprintf(buf, sizeof(buf),
                    "prefill progress: %zu/%zu tokens (%d%%), elapsed %" PRId64 "ms, avg %.1f ms/tok",
                    i + 1, n_prompt,
                    (int)((i + 1) * 100 / n_prompt),
                    now - t_pre0,
                    (i == 0) ? 0.0f : (float)(now - t_pre0) / (float)(i + 1));
                cb.log(cb.ud, buf);
            }
        }

        if (i + 1 == prompt_ids.size()) {
            int tok = fwd_sample(logits.data(), c.vocab_size, temperature, top_p, top_k, seed, pos);
            if (cb.token) {
                std::string piece = qwen_decode_token(model, tok);
                cb.token(cb.ud, tok, piece.data(), (int)piece.size());
            }
            if (tok == c.eos_id)    { reason = "eos"; goto DONE; }
            if (tok == c.im_end_id) { reason = "im_end"; goto DONE; }

            for (int32_t g = 1; g < max_tokens; ++g) {
                int32_t cur = tok;
                if (!fwd_forward_step(&sess, model, cur, pos, logits.data())) {
                    reason = "forward_failed"; goto DONE;
                }
                pos = sess.kv_pos = pos + 1;
                if (pos >= max_pos) { reason = "context_limit"; goto DONE; }

                int nx = fwd_sample(logits.data(), c.vocab_size, temperature, top_p, top_k, seed, pos);
                tok = nx;
                if (cb.token) {
                    std::string piece = qwen_decode_token(model, tok);
                    cb.token(cb.ud, tok, piece.data(), (int)piece.size());
                }
                if (tok == c.eos_id)    { reason = "eos";    break; }
                if (tok == c.im_end_id) { reason = "im_end"; break; }
            }
            break;
        }
    }
DONE:
    if (cb.done) cb.done(cb.ud, reason);
    return nullptr;
}

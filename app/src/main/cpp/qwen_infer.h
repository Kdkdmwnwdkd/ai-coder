// =====================================================
// qwen_infer.h — 极简 Qwen2.5 推理器对外 API
// 目标平台: Android arm64-v8a  +  ggml (CPU/NEON)
// 目标模型: Qwen2.5-1.5B GGUF, Q4_K_M 量化
// =====================================================
#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include <string>

// ---- 模型/超参数 ----
// 默认值对应 Qwen2.5-1.5B-Instruct (与 GGUF 文件元数据不一致时以 GGUF 为准)
struct QwenModelConfig {
    int32_t vocab_size   = 151646;
    int32_t n_embd       = 1536;
    int32_t n_layer      = 28;
    int32_t n_head       = 12;   // query heads
    int32_t n_head_kv    = 2;    // GQA KV heads
    int32_t n_ff         = 8960; // SwiGLU 中间层
    int32_t head_dim     = 128;  // n_embd / n_head
    int32_t rope_dim     = 128;
    int32_t max_seq_len  = 4096;
    float   rms_norm_eps = 1e-6f;
    float   rope_freq_base = 1000000.0f;   // Qwen2 系列
    int32_t bos_id       = 151644;
    int32_t eos_id       = 151643;
    int32_t im_end_id    = 151645;         // ChatML <|im_end|>
    bool    tie_embeddings = true;         // output.weight 复用 token_embd.weight
    bool    kv_f16       = true;
};

// ---- Tokenizer: 字节级 BPE (Qwen 词表, 简化版 bytefallback) ----
struct QwenTokenizer {
    std::vector<std::string>   id_to_token;  // vocab_size
    std::vector<float>         id_to_score;  // merge rank (越小越先合并)
    std::vector<uint32_t>      id_to_type;   // 0=normal, 1=special, 2=byte
    // 反向: token bytes -> token id (编码用)
    //   key 是 piece 的原始字节序列 (包括 bytefallback 的单字节)
    struct BytesHash { size_t operator()(const std::string & s) const noexcept; };
    void * encode_map = nullptr;   // 懒初始化: unordered_map<string,int>
    int32_t vocab_size_stored = 0;
    bool    initialized = false;

    int32_t id(const std::string & piece) const;
    void build_encode_map();
    ~QwenTokenizer();
};

// ---- 权重张量: ggml tensor 的最小包装 (按名字索引) ----
//   数据本身是 mmap 或 malloc 的; 指针持有权交给 QwenModel.
struct QwenTensor {
    std::string name;
    void * data = nullptr;       // 所有权: false=指向 mmap 区域(不free), true=malloc 拷贝
    bool   owned = false;
    int    type  = 0;            // ggml_type (GGML_TYPE_Q4_K_M=13 等)
    int    ndim  = 0;
    size_t ne[4] = {1,1,1,1};    // ggml 顺序: ne[0]=列, ne[1]=行
    size_t nb[4] = {0,0,0,0};    // bytes per row (nb[0]=sizeof(一行元素))
    size_t size_bytes = 0;
};

struct QwenModel {
    QwenModelConfig cfg;
    QwenTokenizer  tok;

    // 原始 GGUF 文件 mmap 区域
    void *  mmap_ptr = nullptr;
    size_t  mmap_len = 0;
    int     mmap_fd  = -1;

    // 所有权权重 malloc 区 (非 mmap 场景)
    std::vector<void *> owned_allocs;

    // 所有权 tensor 对象池
    std::vector<QwenTensor *> tensors;

    // 快速名->指针索引 (懒构建)
    void * name_map = nullptr;  // unordered_map<string, QwenTensor*>

    ~QwenModel();
    QwenTensor * find_tensor(const std::string & name) const;
    void build_name_map();
};

// ---- 推理会话 ----
//   1 次 generate 1 个会话. 内部持有 1 个 ggml_context + KV cache.
struct QwenSession {
    QwenModel * model = nullptr;
    void * ggml_ctx = nullptr;    // struct ggml_context *
    size_t ggml_mem_size = 0;

    // KV cache: 每层 2 个 slot (K, V) 各为 [max_seq, n_head_kv, head_dim]
    //   简单实现: 每个 K/V 是 2D 的 [max_seq, n_head_kv * head_dim] FP16
    std::vector<std::vector<uint16_t>> k_cache;  // [n_layer][max_seq * n_head_kv * head_dim * sizeof(fp16)]
    std::vector<std::vector<uint16_t>> v_cache;

    int32_t kv_pos = 0;          // 下一个写入位置
    int32_t n_threads = 4;       // 默认四核

    ~QwenSession();
    bool init(QwenModel * m);
};

// -------- API --------
// 返回: 成功 nullptr; 失败 返回错误字符串(strdup, 调用者 free).
char * qwen_load_model(const char * gguf_path, QwenModel * & out_model);
void   qwen_free_model(QwenModel * model);

// 词表 ID -> UTF-8 piece
//   注意 special token 原样返回 special token 文本 (如 "<|endoftext|>");
//   上层需要根据 id 跳过 special token 的文本拼接.
std::string qwen_decode_token(const QwenModel * m, int32_t id);

// 文本 -> 一系列 token id (简化 bytefallback BPE)
std::vector<int32_t> qwen_encode_text(const QwenModel * m, const std::string & text, bool add_bos);

// 生成循环: 每个 token 通过 cb_token(id, piece) 回调出去.
//   返回: 成功 nullptr, 失败错误字符串.
//   cb_done(reason): "eos" / "im_end" / "max_tokens"
typedef void (*qwen_cb_token)(void * ud, int32_t id, const char * piece, int32_t piece_len);
typedef void (*qwen_cb_done) (void * ud, const char * reason);
typedef void (*qwen_cb_log)  (void * ud, const char * msg);
struct QwenCallbacks {
    qwen_cb_token token = nullptr;
    qwen_cb_done  done  = nullptr;
    qwen_cb_log   log   = nullptr;
    void * ud = nullptr;
};

char * qwen_generate(
    QwenModel            * model,
    const std::string    & prompt,
    int32_t                max_tokens,
    float                  temperature,
    float                  top_p,
    int32_t                top_k,
    uint32_t               seed,
    const QwenCallbacks  & cb
);

// -------- 可选 debug: 列 GGUF meta+tensor 清单 --------
std::string qwen_dump_model(const QwenModel * m);

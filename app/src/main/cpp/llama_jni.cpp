/*
 * v1.3.25-fix22: 官方最简 llama_jni.cpp
 *
 * 设计原则（完全按用户「死命令」）：
 *   1. 零 sampler_chain 调用 — 只用 llama_model / llama_context 两层官方 C API
 *   2. 手动 argmax 采样（不调 llama_sampler* 任何接口，无 top_k/top_p/temp，最稳路径）
 *   3. n_batch = 1（b5180 魅族 20 上一次喂 >1 token 直接 SIGABRT，硬证据）
 *   4. 手动插 BOS（根治乱码：add_spec=0 之后 tokens.insert(begin(), bos)）
 *   5. ChatML 格式拼 prompt，<|im_start|> / <|im_end|> 用官方 llama_tokenize 识别
 *   6. 全部按 llama.cpp/examples/main.cpp 的调用顺序：
 *        load_model → init_ctx → tokenize(add_spec=0) → kv_clear →
 *        prefill (batch.size=1, one token per decode) → while(gen)
 *
 * JNI 方法签名严格匹配 Kotlin 侧 LlamaJniEngine 的 4 个 external fun：
 *   nativeInit(modelPath, nCtx, nThreads, nGpuLayers) → jlong ctx
 *   nativeRelease(ctx)
 *   nativeChat(ctx, system, user, TokenCallback obj)   // 阻塞 while 循环，回调 onToken/onDone/onError
 *   nativeChatCancel(ctx)
 */

#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdarg>
#include <cstdint>
#include <unistd.h>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"

#define XUEDI_LOG_TAG  "LlamaJni"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  XUEDI_LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  XUEDI_LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, XUEDI_LOG_TAG, __VA_ARGS__)

// =============================================================================
// 全局常量（b5180 魅族 20 踩过的坑，全部硬编码）
// =============================================================================
static constexpr int   SAFE_N_BATCH    = 1;    // 唯一不崩的 batch 大小（硬证据：fix10 n_batch=1 跑通 1024t）
static constexpr int   DEFAULT_MAX_GEN = 800;  // gen 阶段最大 token 数（防止跑飞）
static constexpr int   N_KV_MAX_SHIFT  = 0;    // 预留

// =============================================================================
// TokenCallback Java methodIDs 缓存
// =============================================================================
static JavaVM*            g_vm          = nullptr;
static jclass             g_cbClass     = nullptr;  // LlamaJniEngine$TokenCallback global ref
static jmethodID          g_midOnToken  = nullptr;
static jmethodID          g_midOnDone   = nullptr;
static jmethodID          g_midOnError  = nullptr;
static jmethodID          g_midOnPrefill = nullptr;

// =============================================================================
// C++ 推理状态（每个 loadModel 产出一个 handle）
// =============================================================================
struct LlamaState {
    llama_model*  model;
    llama_context* ctx;
    const llama_vocab* vocab;  // b5180 新增：从 llama_model_get_vocab() 取，生命周期和 model 绑定
    int           n_ctx;
    int           n_threads;
    llama_token   bos;
    llama_token   eos;
    int           n_vocab;

    // cancel flag 按 ctx 粒度（不搞全局，避免并发 loadModel 互相杀）
    std::atomic<bool> cancel;

    LlamaState() : model(nullptr), ctx(nullptr), vocab(nullptr), n_ctx(0), n_threads(4),
                   bos(0), eos(0), n_vocab(0), cancel(false) {}
};

// =============================================================================
// 小工具：Java 字符串 → std::string（UTF-8）
// =============================================================================
static std::string jstring2std(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string r(p ? p : "");
    if (p) env->ReleaseStringUTFChars(s, p);
    return r;
}

// =============================================================================
// 抛 Java RuntimeException（统一错误出口，Kotlin runCatching 能拿到 msg）
// =============================================================================
static void throwJava(JNIEnv* env, const char* fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    LOGE("%s", buf);
    jclass exCls = env->FindClass("java/lang/RuntimeException");
    if (exCls) env->ThrowNew(exCls, buf);
}

// =============================================================================
// 小工具：手动 argmax（零 sampler_chain，官方示例最简采样）
// =============================================================================
static llama_token argmax_sample(const float* logits, int n_vocab) {
    int   best = 0;
    float mx   = logits[0];
    for (int i = 1; i < n_vocab; ++i) {
        if (logits[i] > mx) { mx = logits[i]; best = i; }
    }
    return (llama_token)best;
}

// =============================================================================
// llama_token_to_piece 包装：返回 std::string（处理中文多字节 UTF-8）
// —— b5180 把 vocab 从 llama_model 剥离，统一用 llama_vocab_* 新命名。
// =============================================================================
static std::string tok_to_piece(const llama_vocab* vocab, llama_token tok) {
    char buf[32];
    int n = llama_vocab_token_to_piece(vocab, tok, buf, (int)sizeof(buf), 0, /*special*/false);
    if (n < 0) {
        std::vector<char> big(-n + 2);
        int n2 = llama_vocab_token_to_piece(vocab, tok, big.data(), (int)big.size(), 0, false);
        if (n2 > 0) return std::string(big.data(), n2);
        return "";
    }
    return std::string(buf, (size_t)std::max(0, n));
}

// =============================================================================
// tokenize：add_spec=0（不让 tokenizer 自动加 BOS，我们手动插）
// —— b5180：签名要 const struct llama_vocab* 做第一参数。
// =============================================================================
static std::vector<llama_token> tokenize_prompt(const llama_vocab* vocab, const std::string& text, bool add_special = false) {
    int cap = (int)text.size() + 8;
    std::vector<llama_token> out(cap);
    int n = llama_vocab_tokenize(vocab, text.data(), (int)text.size(),
                                 out.data(), cap, add_special, /*parse_special=*/true);
    if (n < 0) {
        cap = -n + 2;
        out.resize(cap);
        n = llama_vocab_tokenize(vocab, text.data(), (int)text.size(),
                                 out.data(), cap, add_special, true);
    }
    if (n > 0) out.resize((size_t)n); else out.clear();
    return out;
}

// =============================================================================
// 回调 Java 侧 TokenCallback（统一 JNIEnv 从 vm 拿 — nativeChat 跑在 Default 线程池）
// =============================================================================
static JNIEnv* getEnvForThread() {
    JNIEnv* env = nullptr;
    if (!g_vm) return nullptr;
    int stat = g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (stat == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    }
    return env;
}

static void cb_onToken(JNIEnv* env, jobject cb, const std::string& piece) {
    if (!g_midOnToken || !cb) return;
    jstring jp = env->NewStringUTF(piece.c_str());
    env->CallVoidMethod(cb, g_midOnToken, jp);
    env->DeleteLocalRef(jp);
}

static void cb_onDone(JNIEnv* env, jobject cb, const std::string& reason) {
    if (!g_midOnDone || !cb) return;
    jstring jp = env->NewStringUTF(reason.c_str());
    env->CallVoidMethod(cb, g_midOnDone, jp);
    env->DeleteLocalRef(jp);
}

static void cb_onError(JNIEnv* env, jobject cb, const std::string& msg) {
    if (!g_midOnError || !cb) return;
    jstring jp = env->NewStringUTF(msg.c_str());
    env->CallVoidMethod(cb, g_midOnError, jp);
    env->DeleteLocalRef(jp);
}

static void cb_onPrefill(JNIEnv* env, jobject cb, int consumed, int total) {
    if (!g_midOnPrefill || !cb) return;
    env->CallVoidMethod(cb, g_midOnPrefill, (jint)consumed, (jint)total);
}

// =============================================================================
// JNI_OnLoad：缓存 methodID + 执行 llama_backend_init（一次即可）
// =============================================================================
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* env = getEnvForThread();
    if (!env) {
        LOGE("JNI_OnLoad: getEnv FAILED");
        return JNI_ERR;
    }

    // 找 TokenCallback 类（LlamaJniEngine$TokenCallback）
    jclass local = env->FindClass("com/xuedi/coder/model/LlamaJniEngine$TokenCallback");
    if (!local) {
        LOGE("JNI_OnLoad: FindClass LlamaJniEngine$TokenCallback FAILED");
        return JNI_ERR;
    }
    g_cbClass = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);

    g_midOnToken   = env->GetMethodID(g_cbClass, "onToken",         "(Ljava/lang/String;)V");
    g_midOnDone    = env->GetMethodID(g_cbClass, "onDone",          "(Ljava/lang/String;)V");
    g_midOnError   = env->GetMethodID(g_cbClass, "onError",         "(Ljava/lang/String;)V");
    g_midOnPrefill = env->GetMethodID(g_cbClass, "onPrefillProgress","(II)V");

    if (!g_midOnToken || !g_midOnDone || !g_midOnError || !g_midOnPrefill) {
        LOGE("JNI_OnLoad: GetMethodID FAILED (onToken=%p onDone=%p onError=%p onPrefill=%p)",
             g_midOnToken, g_midOnDone, g_midOnError, g_midOnPrefill);
        return JNI_ERR;
    }

    // 官方 llama_backend_init（nuwa params，传 nullptr 用默认）
    LOGI("JNI_OnLoad → llama_backend_init()");
    llama_backend_init();
    LOGI("JNI_OnLoad ✅ 官方最简桥初始化完成。SAFE_N_BATCH=%d 无 sampler_chain 手动 argmax", SAFE_N_BATCH);
    return JNI_VERSION_1_6;
}

// =============================================================================
// nativeInit：加载模型 + 创建 ctx（返回 jlong=LlamaState*）
// =============================================================================
extern "C" JNIEXPORT jlong JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeInit(
        JNIEnv* env, jobject /*thiz*/,
        jstring jpath, jint jnCtx, jint jnThreads, jint /*jGpuLayers*/) {

    std::string path = jstring2std(env, jpath);
    int n_ctx    = jnCtx    > 64 ? (int)jnCtx    : 512;
    int n_threads= jnThreads> 0  ? (int)jnThreads: 4;

    LOGI("nativeInit: path=%s n_ctx=%d n_threads=%d", path.c_str(), n_ctx, n_threads);

    if (path.empty() || access(path.c_str(), R_OK) != 0) {
        throwJava(env, "模型文件不可读：%s (access R_OK 失败)", path.c_str());
        return 0L;
    }

    // ---- 1. llama_model_load_from_file（只传 mparams 基础参数，RoPE 让官方自动识别）----
    llama_model_params mparams = llama_model_default_params();
    // n_gpu_layers 不走（ggml-vulkan OFF），传 0；其余全默认 = 官方自动读 GGUF metadata
    mparams.n_gpu_layers = 0;

    LOGI("nativeInit → llama_model_load_from_file (%s)", path.c_str());
    // —— b5180 新命名：llama_model_load_from_file / llama_model_free
    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        throwJava(env, "llama_model_load_from_file FAILED：GGUF 损坏 / 格式不兼容 / 内部 mmap 失败。\n"
                       "建议：在设置里删模型 → 重新下载 Qwen2.5-1.5B-Instruct-Q4_K_M.gguf");
        return 0L;
    }
    // b5180 必须显式取 vocab 指针（vocab 已经从 model 独立出来）
    const llama_vocab* vocab = llama_model_get_vocab(model);
    LOGI("nativeInit ✅ model loaded。n_vocab=%d n_embd=%d vocab=%p",
         llama_vocab_n_tokens(vocab), llama_model_n_embd(model), (const void*)vocab);

    // ---- 2. llama_init_from_model（cparams.n_batch 强制 SAFE_N_BATCH=1）----
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx       = (uint32_t)n_ctx;
    cparams.n_batch     = (uint32_t)SAFE_N_BATCH;   // 🔴 关键：强制 1，不允许 llama.cpp 内部覆盖
    cparams.n_ubatch    = (uint32_t)SAFE_N_BATCH;
    cparams.n_threads   = (uint32_t)n_threads;
    cparams.n_threads_batch = (uint32_t)n_threads;
    // ⚠️ b5180 里 llama_context_params 已经没有 seed 字段：采样无随机性（argmax），不需要
    cparams.flash_attn  = false;         // 魅族 20 不兼容 flash attn，关
    cparams.offload_kqv = false;         // CPU-only，避免 GPU loader 路径

    LOGI("nativeInit → llama_init_from_model (n_ctx=%u n_batch=%u n_threads=%u flash_attn=0)",
         cparams.n_ctx, cparams.n_batch, cparams.n_threads);
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        throwJava(env, "llama_init_from_model FAILED：KV cache 分配 OOM。\n"
                       "请关后台 App / 重启手机，或改 loadModelRobust L3/L4 档位（n_ctx=1280/768）");
        return 0L;
    }
    LOGI("nativeInit ✅ ctx created。真实 n_ctx=%u 真实 n_batch=%u",
         llama_n_ctx(ctx), llama_n_batch(ctx));

    // ---- 3. 填 state（b5180：bos/eos/n_vocab 统一走 llama_vocab_*，vocab 指针存一份给 chat 用）----
    LlamaState* st = new LlamaState();
    st->model    = model;
    st->ctx      = ctx;
    st->vocab    = vocab;
    st->n_ctx    = (int)llama_n_ctx(ctx);
    st->n_threads= n_threads;
    st->bos      = llama_vocab_bos(vocab);
    st->eos      = llama_vocab_eos(vocab);
    st->n_vocab  = llama_vocab_n_tokens(vocab);
    st->cancel.store(false, std::memory_order_relaxed);

    LOGI("nativeInit 完成：bos=%d eos=%d n_vocab=%d n_ctx=%d vocab=%p",
         st->bos, st->eos, st->n_vocab, st->n_ctx, (const void*)st->vocab);
    return (jlong)st;
}

// =============================================================================
// nativeRelease：释放 ctx + model
// =============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeRelease(
        JNIEnv*, jobject, jlong jhandle) {
    auto* st = (LlamaState*)jhandle;
    if (!st) return;
    LOGI("nativeRelease: ctx=%p model=%p", st->ctx, st->model);
    st->cancel.store(true, std::memory_order_relaxed);
    if (st->ctx)  { llama_free(st->ctx);              st->ctx   = nullptr; }
    // vocab 随 model 一起释放，不需要单独 free；顺序：先 ctx 后 model
    if (st->model){ llama_model_free(st->model);      st->model = nullptr; st->vocab = nullptr; }
    delete st;
    LOGI("nativeRelease ✅ done");
}

// =============================================================================
// nativeChatCancel：置取消 flag
// =============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeChatCancel(
        JNIEnv*, jobject, jlong jhandle) {
    auto* st = (LlamaState*)jhandle;
    if (!st) return;
    st->cancel.store(true, std::memory_order_relaxed);
    LOGI("nativeChatCancel: flag = true");
}

// =============================================================================
// nativeChat：主循环（阻塞 JNI，内部 while 调 onToken 流式输出）
//
// 完全按 examples/main.cpp 最简顺序：
//   1. ChatML 拼 prompt："<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n"
//   2. llama_tokenize(..., add_special=false, parse_special=true)
//   3. tokens.insert(begin(), bos)   ← 手动插，根治乱码
//   4. llama_kv_cache_clear(ctx)     ← 修"第二次没反应"bug
//   5. prefill：batch_init(1,0,1) + batch.n_tokens=1 + llama_decode(ctx, batch)
//      （逐个 token 过 batch，虽然慢但 100% 不崩）
//   6. gen：batch 只有 last token → decode → argmax → accept → to_piece → onToken
// =============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeChat(
        JNIEnv* env, jobject, jlong jhandle,
        jstring jSystem, jstring jUser, jobject jCb) {

    auto* st = (LlamaState*)jhandle;
    if (!st || !st->model || !st->ctx) {
        throwJava(env, "nativeChat: ctx=0（模型未初始化）");
        return;
    }
    // 用户按取消？
    st->cancel.store(false, std::memory_order_relaxed);

    JNIEnv* tenv = getEnvForThread();
    if (!tenv) tenv = env;
    jobject gCb = jCb ? tenv->NewGlobalRef(jCb) : nullptr;
    if (!gCb) { LOGE("nativeChat: NewGlobalRef(callback) FAILED"); return; }

    std::string system = jstring2std(env, jSystem);
    std::string user   = jstring2std(env, jUser);
    if (system.empty()) system = "你是一个聪明、简洁、专业的AI编程助手，用中文回答用户的问题。";

    // ---- Step 1: ChatML 拼接 ----
    char prompt_buf[1 << 15];   // 32KB，983 token × 30byte ≈ 30KB 够
    int pn = snprintf(prompt_buf, sizeof(prompt_buf),
        "<|im_start|>system\n%s<|im_end|>\n<|im_start|>user\n%s<|im_end|>\n<|im_start|>assistant\n",
        system.c_str(), user.c_str());
    std::string prompt(prompt_buf, (size_t)std::max(0, pn));
    LOGI("nativeChat prompt[%zu B] 系统=%zu 用户=%zu", prompt.size(), system.size(), user.size());

    // ---- Step 2: tokenize（add_special=false，parse_special=true 识别 <|im_start|> 为单个 token）----
    // b5180：第一参数必须是 vocab（从 model 拆出来的独立对象）
    std::vector<llama_token> tokens = tokenize_prompt(st->vocab, prompt, /*add_special=*/false);
    if (tokens.empty()) {
        cb_onError(tenv, gCb, "tokenize 返回空：prompt 可能含不支持的特殊字节");
        tenv->DeleteGlobalRef(gCb);
        return;
    }
    LOGI("nativeChat tokenize -> %zu tokens。首 token=%d (期望 bos 手动插后改)",
         tokens.size(), (int)tokens[0]);

    // ---- Step 3: 手动插 BOS（乱码根治。add_spec=0 时 tokenizer 不自动加）----
    if (tokens.empty() || tokens[0] != st->bos) {
        tokens.insert(tokens.begin(), st->bos);
        LOGI("nativeChat: ✂️ 手动插 BOS id=%d，tokens 总数=%zu", st->bos, tokens.size());
    } else {
        LOGI("nativeChat: token[0] 已是 BOS(%d)，跳过手动插", st->bos);
    }

    // prompt 过长：截末尾（给 gen 阶段至少留 DEFAULT_MAX_GEN token）
    int max_prompt = st->n_ctx - DEFAULT_MAX_GEN - 4;
    if ((int)tokens.size() > max_prompt) {
        LOGW("nativeChat: prompt 过长 %zu > %d，截断末尾", tokens.size(), max_prompt);
        tokens.erase(tokens.begin(), tokens.end() - max_prompt);
        if (tokens[0] != st->bos) tokens.insert(tokens.begin(), st->bos);
    }

    // ---- Step 4: 清 KV cache（修"二次对话不吐字"bug）
    // b5180 新命名：llama_kv_self_clear（旧 llama_kv_cache_clear 仍 deprecated，这里用新命名避免 warning）
    llama_kv_self_clear(st->ctx);
    LOGI("nativeChat: kv cache 已清。开始 prefill [%zu tokens]", tokens.size());

    // ---- Step 5: batch init（固定 SAFE_N_BATCH=1，官方最简：n_tokens_max=1, n_seq_max=1, embd=0）----
    // llama_batch_init(n_tokens_max, n_seq_max, embd)
    // n_seq_max 必须 >= 1：因为后续 batch.seq_id[0][0] = 0，b5180 内部 GGML_ASSERT(seq_id < n_seq_max)
    // embd=0: 正常 token 输入（不是 embedding 输入）
    llama_batch batch = llama_batch_init(SAFE_N_BATCH, 1, 0);
    LOGI("nativeChat: batch created (n_tokens_max=%d, n_seq_max=1, embd=0)。SAFE 路径：batch.n_tokens 永远 =1", SAFE_N_BATCH);

    int n_past = 0;
    const int total_prefill = (int)tokens.size();
    std::string fullOut;
    llama_token last_tok = 0;  // ✅ 提前声明：避免 prefill 阶段 "goto cleanup" 跨过变量初始化（C++ 编译硬错）

    // ---- 5.1 PREFILL：每个 token 单独走 llama_decode ----
    for (int i = 0; i < (int)tokens.size(); ++i) {
        if (st->cancel.load(std::memory_order_relaxed)) {
            cb_onError(tenv, gCb, "用户取消（prefill 阶段）");
            goto cleanup;
        }
        // batch 填法严格按官方示例：1 token, pos=i, 0 个 seq_id（但 logits 只取最后一个需要 n_seq_id>0）
        // 修正：n_seq_id=1，seq_id[0]=0
        batch.n_tokens = 1;
        batch.token[0] = tokens[i];
        batch.pos[0]   = i;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        // 只最后一个 prompt token 需要 logits（prefill 阶段除了最后一个都是纯 KV 写入，不用 logits）
        batch.logits[0] = (i == (int)tokens.size() - 1) ? 1 : 0;

        if ((i & 31) == 0 || i == (int)tokens.size() - 1) {
            LOGI("⏳ prefill #%d/%d  pos=%d token=%d logits=%d",
                 i+1, total_prefill, batch.pos[0], (int)batch.token[0], batch.logits[0]);
        }

        int ret = llama_decode(st->ctx, batch);
        if (ret != 0) {
            char msg[256];
            snprintf(msg, sizeof(msg),
                "prefill llama_decode FAIL ret=%d (i=%d/%d token=%d pos=%d)。"
                "常见：n_ctx 溢出 / KV OOM / b5180 batch assertion",
                ret, i+1, total_prefill, (int)batch.token[0], batch.pos[0]);
            LOGE("%s", msg);
            cb_onError(tenv, gCb, msg);
            goto cleanup;
        }
        n_past = i + 1;
        // 每 8 个 token 回一次进度（UI 不白）
        if ((i & 7) == 7 || i == (int)tokens.size() - 1) {
            cb_onPrefill(tenv, gCb, i + 1, total_prefill);
        }
    }
    LOGI("✅ prefill 完成。n_past=%d 开始生成（max=%d tokens）", n_past, DEFAULT_MAX_GEN);

    // ---- Step 6: GENERATION ----
    // （last_tok 已在 prefill 循环之前声明为 0，避免 goto 跨初始化报错）
    {   // 取 prefill 后最后一个 token 的 logits → argmax → 首个 gen token
        const float* logits = llama_get_logits_ith(st->ctx, 0);
        if (!logits) {
            cb_onError(tenv, gCb, "generation: llama_get_logits_ith 返回空（prefill 最后一个 logits=0？）");
            goto cleanup;
        }
        last_tok = argmax_sample(logits, st->n_vocab);
    }

    for (int step = 0; step < DEFAULT_MAX_GEN; ++step) {
        if (st->cancel.load(std::memory_order_relaxed)) {
            cb_onDone(tenv, gCb, "cancel");
            goto cleanup;
        }
        if (last_tok == st->eos) {
            LOGI("generation: hit EOS at step=%d", step);
            cb_onDone(tenv, gCb, "eos");
            goto cleanup;
        }
        if (n_past >= st->n_ctx - 2) {
            LOGW("generation: n_past=%d 接近 n_ctx=%d，提前 stop", n_past, st->n_ctx);
            cb_onDone(tenv, gCb, "stop (n_ctx_limit)");
            goto cleanup;
        }

        // ---- piece 回传 ----
        // b5180：tok_to_piece 统一走 vocab（不再需要 model 指针）
        std::string piece = tok_to_piece(st->vocab, last_tok);
        if (!piece.empty()) {
            fullOut += piece;
            cb_onToken(tenv, gCb, piece);
        }

        // ---- next decode（最后生成的 token 作为 next input）----
        batch.n_tokens = 1;
        batch.token[0] = last_tok;
        batch.pos[0]   = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        int ret = llama_decode(st->ctx, batch);
        if (ret != 0) {
            char msg[256];
            snprintf(msg, sizeof(msg), "gen llama_decode FAIL ret=%d step=%d n_past=%d",
                     ret, step, n_past);
            LOGE("%s", msg);
            cb_onError(tenv, gCb, msg);
            goto cleanup;
        }
        n_past++;

        // ---- argmax ----
        const float* logits = llama_get_logits_ith(st->ctx, 0);
        if (!logits) {
            cb_onError(tenv, gCb, "generation: logits_ith 返回空");
            goto cleanup;
        }
        last_tok = argmax_sample(logits, st->n_vocab);
    }

    cb_onDone(tenv, gCb, "max_tokens");
    LOGI("nativeChat ✅ 正常结束（max_tokens）。输出 %zu bytes，共 %d tokens",
         fullOut.size(), DEFAULT_MAX_GEN);

cleanup:
    llama_batch_free(batch);
    if (gCb) tenv->DeleteGlobalRef(gCb);
    LOGI("nativeChat cleanup ✅");
    (void)fullOut;
}

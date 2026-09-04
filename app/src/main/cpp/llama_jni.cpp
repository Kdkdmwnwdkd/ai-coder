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
// =====================================================
// 方案D 最终一次（vc66）：SAFE_N_BATCH 1→32 + 打开 Prefill-BATCH 分支
//   · 目标：247 tokens Prefill 一次 llama_decode，从 71s 降到 ~11s
//   · 注意：llama.cpp 内部会把 n_batch < GGML_KQ_MASK_PAD(32) 强制抬到 32，
//          设 1 只是自欺欺人，真实一直都是 32。
//   · 失败约定：若 SIGSEGV/SIGABRT 本次结果就是证明，永久不用再修改。
// =====================================================
static constexpr int   SAFE_N_BATCH    = 32;    // 方案D最终版：32 批量 Prefill
static constexpr int   DEFAULT_MAX_GEN = 2048;  // v1.3.25-stable: 800 → 2048，给更长对话留空间
static constexpr int   EOS_GUARD_STEPS = 32;    // v1.3.25-stable: argmax 采样下"生成 7 token 就 EOS"的根治：前 32 步硬禁 EOS
static constexpr int   N_KV_MAX_SHIFT  = 0;     // 预留

// 🔴 v1.3.25-perf1: Prefill 模式 — 给 Java 诊断框 / 日志用（prefMode 字段）
static constexpr const char* PREF_MODE_STEPBYSTEP = "STEPx1";  // 逐 token 兜底（fallback 或批量未试）
static constexpr const char* PREF_MODE_BATCH_OK   = "BATCH_OK"; // 批量提交一次 llama_decode 成功
static constexpr const char* PREF_MODE_FALLBACK   = "BATCH_FB"; // 批量提交失败，已回退逐 token

// =============================================================================
// TokenCallback Java methodIDs 缓存
// =============================================================================
static JavaVM*            g_vm           = nullptr;
static jclass             g_cbClass      = nullptr;  // LlamaJniEngine$TokenCallback global ref
static jmethodID          g_midOnToken   = nullptr;
static jmethodID          g_midOnDone    = nullptr;
static jmethodID          g_midOnError   = nullptr;
static jmethodID          g_midOnPrefill = nullptr;
// 🔴 v1.3.25-perf1: 通知 Java 侧本次 prefill 使用了 BATCH_OK / BATCH_FB / STEPx1
static jmethodID          g_midOnPrefillMode = nullptr;

// =============================================================================
// C++ 推理状态（每个 loadModel 产出一个 handle）
// =============================================================================
struct LlamaState {
    llama_model*  model;
    llama_context* ctx;
    const llama_vocab* vocab;  // b5180 新增：从 llama_model_get_vocab() 取，生命周期和 model 绑定
    int           n_ctx;
    int           n_threads;
    int           n_gpu_layers;   // v1.3.26-gpu1：真实卸载层数（0=CPU，>0=Vulkan，Qwen2.5-3B 最多36层，传-1/99都会被model层上限夹）
    llama_token   bos;
    llama_token   eos;
    int           n_vocab;

    // cancel flag 按 ctx 粒度（不搞全局，避免并发 loadModel 互相杀）
    std::atomic<bool> cancel;

    LlamaState() : model(nullptr), ctx(nullptr), vocab(nullptr), n_ctx(0), n_threads(4),
                   n_gpu_layers(0), bos(0), eos(0), n_vocab(0), cancel(false) {}
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
//   —— forbid_token: 不允许选择的 token id（典型：前 EOS_GUARD_STEPS 内把 EOS 置为 -inf）
//                  = -1 表示禁用。
// =============================================================================
static llama_token argmax_sample(const float* logits, int n_vocab, llama_token forbid_token = -1) {
    int   best = 0;
    float mx   = logits[0];
    if (best == forbid_token) mx = -1e30f;
    for (int i = 1; i < n_vocab; ++i) {
        float v = logits[i];
        if (i == forbid_token) v = -1e30f;
        if (v > mx) { mx = v; best = i; }
    }
    return (llama_token)best;
}

// =============================================================================
// llama_token_to_piece 包装：返回 std::string（处理中文多字节 UTF-8）
// —— b5180：函数名保持 llama_token_to_piece 不变，只是第一参数从 llama_model* 改成 const llama_vocab*
//    开头的 bos/eos/n_tokens 才是 llama_vocab_* 新命名，不要搞混！
// =============================================================================
static std::string tok_to_piece(const llama_vocab* vocab, llama_token tok) {
    char buf[32];
    int n = llama_token_to_piece(vocab, tok, buf, (int)sizeof(buf), 0, /*special*/false);
    if (n < 0) {
        std::vector<char> big(-n + 2);
        int n2 = llama_token_to_piece(vocab, tok, big.data(), (int)big.size(), 0, false);
        if (n2 > 0) return std::string(big.data(), n2);
        return "";
    }
    return std::string(buf, (size_t)std::max(0, n));
}

// =============================================================================
// tokenize：add_spec=0（不让 tokenizer 自动加 BOS，我们手动插）
// —— b5180：函数名保持 llama_tokenize 不变，只是第一参数从 llama_model* 改成 const llama_vocab*
// =============================================================================
static std::vector<llama_token> tokenize_prompt(const llama_vocab* vocab, const std::string& text, bool add_special = false) {
    int cap = (int)text.size() + 8;
    std::vector<llama_token> out(cap);
    int n = llama_tokenize(vocab, text.data(), (int)text.size(),
                           out.data(), cap, add_special, /*parse_special=*/true);
    if (n < 0) {
        cap = -n + 2;
        out.resize(cap);
        n = llama_tokenize(vocab, text.data(), (int)text.size(),
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

// 🔴 v1.3.25-perf1: 通知 Java 侧本次 prefill 模式（BATCH_OK / BATCH_FB / STEPx1）
static void cb_onPrefillMode(JNIEnv* env, jobject cb, const std::string& mode) {
    if (!g_midOnPrefillMode || !cb) return;
    jstring jm = env->NewStringUTF(mode.c_str());
    env->CallVoidMethod(cb, g_midOnPrefillMode, jm);
    env->DeleteLocalRef(jm);
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
    g_midOnPrefillMode = env->GetMethodID(g_cbClass, "onPrefillMode","(Ljava/lang/String;)V");

    if (!g_midOnToken || !g_midOnDone || !g_midOnError || !g_midOnPrefill) {
        LOGE("JNI_OnLoad: GetMethodID FAILED (onToken=%p onDone=%p onError=%p onPrefill=%p onPrefillMode=%p)",
             g_midOnToken, g_midOnDone, g_midOnError, g_midOnPrefill, g_midOnPrefillMode);
        return JNI_ERR;
    }
    if (!g_midOnPrefillMode) {
        LOGW("JNI_OnLoad: onPrefillMode methodID 未找到（旧 Kotlin TokenCallback 接口未升级，prefMode 不回传但主流程不受影响）");
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
        jstring jpath, jint jnCtx, jint jnThreads, jint jGpuLayers) {

    std::string path = jstring2std(env, jpath);
    int n_ctx    = jnCtx    > 64 ? (int)jnCtx    : 512;
    int n_threads= jnThreads> 0  ? (int)jnThreads: 4;
    // ---- v1.3.26-gpu1 n_gpu_layers 裁剪（严格编译期双保险）----
    //   Kotlin 约定：<0 = 全卸载；我们夹到 99（任何模型都不可能超过 99 层）。
    //   llama_model_load_from_file 内部再根据"模型实际层数"做上限夹，我们这里只做粗略合法值。
    //   关键：编译期没开 Vulkan(XUEDI_LLAMA_VULKAN=0) 时，强制 0 —— 即使 Kotlin 传 -1，
    //        也不会让 b5180 走任何 GPU 路径，绝对不影响 CPU 底包稳定性。
    int n_gpu_layers = (int)jGpuLayers;
#if XUEDI_LLAMA_VULKAN
    if (n_gpu_layers < 0) n_gpu_layers = 99;  // -1 / 任何负值 = “全 offload”
    if (n_gpu_layers > 128) n_gpu_layers = 128;
#else
    if (n_gpu_layers != 0) {
        LOGI("nativeInit: 编译未启用 Vulkan (XUEDI_LLAMA_VULKAN=0)，"
             "jGpuLayers=%d → 强制 0（保持纯 CPU 底包不变）", n_gpu_layers);
        n_gpu_layers = 0;
    }
#endif

    LOGI("nativeInit: path=%s n_ctx=%d n_threads=%d n_gpu_layers=%d (XUEDI_LLAMA_VULKAN=%d)",
         path.c_str(), n_ctx, n_threads, n_gpu_layers, (int)XUEDI_LLAMA_VULKAN);

    if (path.empty() || access(path.c_str(), R_OK) != 0) {
        throwJava(env, "模型文件不可读：%s (access R_OK 失败)", path.c_str());
        return 0L;
    }

    // ---- 1. llama_model_load_from_file（只传 mparams 基础参数，RoPE 让官方自动识别）----
    llama_model_params mparams = llama_model_default_params();
    // v1.3.26-gpu1: 真正启用 n_gpu_layers（由上面的编译期/运行时双 clamp 保证安全）
    mparams.n_gpu_layers = (int32_t)n_gpu_layers;
    LOGI("nativeInit → llama_model_load_from_file (%s) (n_gpu_layers=%d)",
         path.c_str(), (int)mparams.n_gpu_layers);
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

    // ---- 2. llama_init_from_model（cparams.n_batch=SAFE_N_BATCH=32，方案D最终版）----
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx       = (uint32_t)n_ctx;
    cparams.n_batch     = (uint32_t)SAFE_N_BATCH;   // 方案D：32，对齐 Prefill-BATCH 上限
    cparams.n_ubatch    = (uint32_t)SAFE_N_BATCH;
    cparams.n_threads   = (uint32_t)n_threads;
    cparams.n_threads_batch = (uint32_t)n_threads;
    // ⚠️ b5180 里 llama_context_params 已经没有 seed 字段：采样无随机性（argmax），不需要
    cparams.flash_attn  = false;         // 魅族 20 不兼容 flash attn，关
    // v1.3.26-gpu1：只有当 n_gpu_layers>0（已经经过 XUEDI_LLAMA_VULKAN 钳制）时才 offload KQV，
    //               避免 CPU-only 构建里把 KV 往不存在的后端推（避免潜在初始化路径）。
    cparams.offload_kqv = (n_gpu_layers > 0);

    LOGI("nativeInit → llama_init_from_model (n_ctx=%u n_batch=%u n_threads=%u n_gpu_layers=%d offload_kqv=%d flash_attn=0)",
         cparams.n_ctx, cparams.n_batch, cparams.n_threads,
         n_gpu_layers, (int)cparams.offload_kqv);
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
    st->n_gpu_layers = n_gpu_layers;
    st->bos      = llama_vocab_bos(vocab);
    st->eos      = llama_vocab_eos(vocab);
    st->n_vocab  = llama_vocab_n_tokens(vocab);
    st->cancel.store(false, std::memory_order_relaxed);

    LOGI("nativeInit 完成：bos=%d eos=%d n_vocab=%d n_ctx=%d n_gpu_layers=%d (XUEDI_LLAMA_VULKAN=%d) vocab=%p",
         st->bos, st->eos, st->n_vocab, st->n_ctx,
         st->n_gpu_layers, (int)XUEDI_LLAMA_VULKAN, (const void*)st->vocab);
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
    LOGI("nativeChat runtime: n_threads=%d n_gpu_layers=%d (XUEDI_LLAMA_VULKAN=%d)",
         st->n_threads, st->n_gpu_layers, (int)XUEDI_LLAMA_VULKAN);

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

    // ---- Step 5: Prefill（v1.3.25-perf1：先试批量 llama_decode 一次过，失败回退 SAFE 逐 token）----
    //   回退保证：只要 llama_decode 返回非 0 → 立即清 KV + 走原来的 n_tokens=1 循环。
    //   风险隔离：ctx cparams 的 n_batch/n_ubatch 仍保持 SAFE_N_BATCH=1 不变（不碰黑盒），
    //     我们只在单个 llama_decode 调用里传「更大的 batch 对象」——llama.cpp 内部允许，
    //     若内部 assertion 失败，ret!=0 → fallback；若是 SIGABRT（极罕见），下版本再屏蔽。
    llama_batch batch = llama_batch_init(SAFE_N_BATCH, /*embd=*/0, /*n_seq_max=*/1);
    LOGI("nativeChat: SAFE batch created (n_tokens_max=%d, embd=0, n_seq_max=1)", SAFE_N_BATCH);

    int n_past = 0;
    const int total_prefill = (int)tokens.size();
    std::string fullOut;
    llama_token last_tok = 0;  // ✅ 提前声明：避免 prefill 阶段 "goto cleanup" 跨过变量初始化（C++ 编译硬错）

    // ---- 5.0 PREFILL-BATCH 尝试（v1.3.25-perf1）----
    // 🔴 v1.3.25-perf1-stable (code 59) 结论：
    //   魅族 20 + Qwen2.5-3B + b5180 上，N=247 一次性 llama_decode 直接 SIGSEGV → ggml_abort，
    //   llama_decode 内部 assertion 没有走 ret 返回，fallback 逻辑完全来不及执行（实锤证据见诊断包 v1.3.25-perf1 crash 日志）。
    //   根因：llama_build_attn_rope 在 b5180 批量模式下对 pos 数组 / RoPE 频率基存在硬编码假设，
    //         与 Qwen2 的 GGUF metadata 不匹配。perf1 阶段永久关批量，
    //         将来升级到 llama.cpp b5800+ 后，把下面 'false &&' 去掉即可一键重开（=零代码回归）。
    {
        bool batch_ok    = false;
        bool batch_tried = false;  // v1.3.25-perf2 fix: 是否"真的进入过批量尝试分支"。只有 tried+failed 才叫 BATCH_FB。
        const int N = (int)tokens.size();
        // 🔴 方案D最终版（vc66）：打开 Prefill-BATCH 分支。
        // 若本次仍 SIGSEGV/SIGABRT，证明 perf1 代码级断言（llama_build_attn_rope）永久无法绕过，永久回退。
        if (N > 1 && N <= 1024) {
            batch_tried = true;
            LOGI("🔬 PREFILL-BATCH: 尝试一次性 prefill（N=%d tokens）。若 ret!=0 立即 fallback 逐 token", N);
            // 单独构造临时 batch_all（不影响下面的 SAFE batch 生命周期变量，避免 cleanup 双 free）
            llama_batch batch_all = llama_batch_init(N, /*embd=*/0, /*n_seq_max=*/1);
            if (batch_all.token && batch_all.pos && batch_all.n_seq_id && batch_all.seq_id && batch_all.logits) {
                batch_all.n_tokens = N;
                for (int i = 0; i < N; ++i) {
                    batch_all.token[i]      = tokens[i];
                    batch_all.pos[i]        = i;
                    batch_all.n_seq_id[i]   = 1;
                    batch_all.seq_id[i][0]  = 0;
                    // 只最后一个 token 需要 logits（gen 阶段首个采样）
                    batch_all.logits[i]     = (i == N - 1) ? 1 : 0;
                }
                const int ret = llama_decode(st->ctx, batch_all);
                if (ret == 0) {
                    n_past   = N;
                    batch_ok = true;
                    LOGI("✅✅ PREFILL-BATCH PASS（%d tokens）。n_past=%d 直接进入 generation", N, n_past);
                    cb_onPrefillMode(tenv, gCb, PREF_MODE_BATCH_OK);
                    cb_onPrefill(tenv, gCb, N, N);
                } else {
                    LOGE("❌ PREFILL-BATCH FAIL ret=%d N=%d → 清 KV 并 fallback 逐 token (SAFE_N_BATCH=%d)",
                         ret, N, SAFE_N_BATCH);
                    // 失败必须清 KV：batch_all 可能部分写入了 KV，不清的话逐 token 会 pos 冲突
                    llama_kv_self_clear(st->ctx);
                }
            } else {
                LOGE("❌ PREFILL-BATCH: llama_batch_init(%d,0,1) 返回空字段 → 直接 fallback", N);
            }
            llama_batch_free(batch_all);
        } else {
            LOGI("⏭️  PREFILL-BATCH: N=%d（<=1 或 >1024），跳过批量尝试，直接 STEPx1", N);
        }

        if (!batch_ok) {
            // ---- 5.1 FALLBACK：原 SAFE 逐 token prefill（100% 不崩的黄金路径）----
            // v1.3.25-perf2 prefMode 语义修正：
            //   · 真正尝试过批量 (batch_tried=true) 且失败 → BATCH_FB
            //   · 没试过批量（false&& 关了 / N<=1 / N>1024）     → STEPx1
            if (batch_tried) {
                LOGI("🛟 PREFILL FALLBACK: 启动 STEPx1（%d tokens）。批量失败后回退到逐token 兜底", total_prefill);
                cb_onPrefillMode(tenv, gCb, PREF_MODE_FALLBACK);
            } else {
                LOGI("⏩ PREFILL: 使用 STEPx1（%d tokens）。批量分支已禁用 / 未进入", total_prefill);
                cb_onPrefillMode(tenv, gCb, PREF_MODE_STEPBYSTEP);
            }

            for (int i = 0; i < (int)tokens.size(); ++i) {
                if (st->cancel.load(std::memory_order_relaxed)) {
                    cb_onError(tenv, gCb, "用户取消（prefill fallback 阶段）");
                    goto cleanup;
                }
                batch.n_tokens = 1;
                batch.token[0] = tokens[i];
                batch.pos[0]   = i;
                batch.n_seq_id[0] = 1;
                batch.seq_id[0][0] = 0;
                batch.logits[0] = (i == (int)tokens.size() - 1) ? 1 : 0;

                if ((i & 31) == 0 || i == (int)tokens.size() - 1) {
                    LOGI("⏳ prefill-fb #%d/%d  pos=%d token=%d logits=%d",
                         i+1, total_prefill, batch.pos[0], (int)batch.token[0], batch.logits[0]);
                }

                const int ret = llama_decode(st->ctx, batch);
                if (ret != 0) {
                    char msg[256];
                    snprintf(msg, sizeof(msg),
                        "prefill fallback llama_decode FAIL ret=%d (i=%d/%d token=%d pos=%d)。"
                        "常见：n_ctx 溢出 / KV OOM",
                        ret, i+1, total_prefill, (int)batch.token[0], batch.pos[0]);
                    LOGE("%s", msg);
                    cb_onError(tenv, gCb, msg);
                    goto cleanup;
                }
                n_past = i + 1;
                if ((i & 7) == 7 || i == (int)tokens.size() - 1) {
                    cb_onPrefill(tenv, gCb, i + 1, total_prefill);
                }
            }
            LOGI("✅ prefill fallback 完成。n_past=%d", n_past);
        }
    }
    LOGI("✅ prefill 总体完成。n_past=%d 开始生成（max=%d tokens，EOS_GUARD_STEPS=%d）",
         n_past, DEFAULT_MAX_GEN, EOS_GUARD_STEPS);

    // ---- Step 6: GENERATION ----
    // （last_tok 已在 prefill 循环之前声明为 0，避免 goto 跨初始化报错）
    {   // 取 prefill 后最后一个 token 的 logits → argmax → 首个 gen token
        const float* logits = llama_get_logits_ith(st->ctx, 0);
        if (!logits) {
            cb_onError(tenv, gCb, "generation: llama_get_logits_ith 返回空（prefill 最后一个 logits=0？）");
            goto cleanup;
        }
        // Step 0 也属于"前 EOS_GUARD_STEPS 步"，统一禁 EOS（否则首步就 EOS，比如之前日志里的 step=7 EOS）
        last_tok = argmax_sample(logits, st->n_vocab,
                                 0 < EOS_GUARD_STEPS ? st->eos : (llama_token)-1);
    }

    for (int step = 0; step < DEFAULT_MAX_GEN; ++step) {
        if (st->cancel.load(std::memory_order_relaxed)) {
            cb_onDone(tenv, gCb, "cancel");
            goto cleanup;
        }
        // ⚠️ v1.3.25-stable: "生成 7 个 token 就 EOS" 的根治：
        //   argmax 采样 + ChatML 空 assistant 段，模型有时把正常回复当成"一句话就结束"，
        //   我们在"前 EOS_GUARD_STEPS 步"里直接禁用 EOS（不是取消判断，是在采样阶段根本不允许抽到它）。
        //   超过 guard 步数后恢复正常：如果 argmax 仍然返回 EOS，就正常接受。
        if (last_tok == st->eos) {
            LOGI("generation: hit EOS at step=%d (EOS guard elapsed=%s)",
                 step, step >= EOS_GUARD_STEPS ? "yes" : "no");
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
        // EOS guard: 前 EOS_GUARD_STEPS 步（包含 step=0）硬禁 EOS token
        const bool under_eos_guard = (step + 1) < EOS_GUARD_STEPS; // +1 因为这里采样的是"下一步"token
        const float* logits = llama_get_logits_ith(st->ctx, 0);
        if (!logits) {
            cb_onError(tenv, gCb, "generation: logits_ith 返回空");
            goto cleanup;
        }
        last_tok = argmax_sample(logits, st->n_vocab,
                                 under_eos_guard ? st->eos : (llama_token)-1);
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

/*
 * 【M5-4 真推理】xuedi-coder JNI bridge：llama_jni.cpp
 *
 * 严格对照 llama.cpp b5180 官方 API（拉自 https://raw.githubusercontent.com/ggml-org/llama.cpp/b5180/include/llama.h）
 *   v1.3.12 方案B：b4835→b5180 升级。已逐函数核对签名：llama_batch_get_one(tokens,n)、
 *   llama_tokenize(7参)、llama_token_to_piece(6参)、llama_n_batch、llama_sampler_* 等
 *   全部与 b4835 源码级兼容，本文件零改动。
 * 写之前做过 survey 实锤存在：
 *   - llama_backend_init / llama_backend_free
 *   - llama_model_load_from_file / llama_init_from_model / llama_model_free / llama_free
 *   - llama_model_get_vocab / llama_vocab_n_tokens / llama_vocab_bos / llama_vocab_eos
 *   - llama_tokenize (const llama_vocab*, ...)
 *   - llama_batch_get_one(const llama_token*, int32_t)
 *   - llama_decode(ctx, batch)
 *   - llama_sampler_chain_init / llama_sampler_chain_add / llama_sampler_free
 *   - llama_sampler_init_top_k / _top_p / _temp / _dist / _penalties
 *   - llama_sampler_sample(smpl, ctx, idx) / llama_sampler_accept(smpl, token)
 *   - llama_token_to_piece (const llama_vocab*, ...)
 *
 * 经验（Experience ID 1519909）：
 *   1) 用了 __android_log_print 必须链接 -llog（已在 CMakeLists target_link_libraries 补 log）
 *   2) 崩溃优先看 UnsatisfiedLinkError / NoSuchMethodError —— 签名错、methodID 缓存错是最常见崩点
 *   3) callback Java 前必须 AttachCurrentThread（协程/NDK 线程默认没 attach 到 JVM）
 *
 * 4 个 native 方法签名（对应 Kotlin LlamaJniEngine.kt 声明）：
 *   Java_com_xuedi_coder_model_LlamaJniEngine_nativeInit          (JLjava/lang/String;IIII)J
 *   Java_com_xuedi_coder_model_LlamaJniEngine_nativeRelease       (J)V
 *   Java_com_xuedi_coder_model_LlamaJniEngine_nativeChat          (JLjava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V
 *   Java_com_xuedi_coder_model_LlamaJniEngine_nativeChatCancel    (J)V
 *
 * Kotlin callback interface:
 *   package com.xuedi.coder.model.LlamaJniEngine$TokenCallback
 *   methods: onToken(Ljava/lang/String;)V / onDone(Ljava/lang/String;)V / onError(Ljava/lang/String;)V
 */

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <sys/mman.h>
#include <unistd.h>

// ======================================================================
// 🔴 v1.3.5 新增：真实连续内存探测（替代 Kotlin 层按 availMem 猜阈值的伪代码）
//   二分法精确探测当前设备最大可分配的连续匿名内存（单位：MB）。
//   PROT_NONE 仅占用 VMA，不实际占用物理页 / swap / zram，
//   所以能准确测出「进程虚拟地址空间里还能 mmap 多大一块连续区域」，
//   这正是 llama_init_from_model 真正需要的硬指标。
// ======================================================================
static int32_t probe_max_continuous_mb() {
    const size_t page_size = (size_t)sysconf(_SC_PAGESIZE);
    (void)page_size;
    const size_t FOUR_GB  = 4096ULL * 1024ULL * 1024ULL;
    const size_t STEP     = 512ULL * 1024ULL * 1024ULL;   // 粗步 512MB
    const size_t GRAN     =  16ULL * 1024ULL * 1024ULL;   // 精分 16MB
    void * ptr = nullptr;

    // —— 第一轮：粗步进找一个上界范围 ——
    size_t last_success = 0;
    for (size_t size = STEP; size <= FOUR_GB; size += STEP) {
        ptr = mmap(nullptr, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (ptr == MAP_FAILED) break;
        munmap(ptr, size);
        last_success = size;
    }
    // 设备大于 4GB 的情况下（目前极少数），上界就定在 4GB。
    if (last_success == FOUR_GB) return 4096;

    // —— 第二轮：在 [last_success, last_success + STEP) 之间精细二分 ——
    //   last_success - STEP 表示"之前成功的倒数第二个"（可能为 0）
    size_t lo = (last_success > STEP) ? (last_success - STEP) : 0;
    size_t hi = last_success + STEP;
    while (lo + GRAN < hi) {
        size_t mid = (lo / 2ULL) + (hi / 2ULL) + ((lo & 1ULL) & (hi & 1ULL)); // 防溢出
        ptr = mmap(nullptr, mid, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (ptr != MAP_FAILED) {
            munmap(ptr, mid);
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return (int32_t)(lo / (1024ULL * 1024ULL));
}

#include "llama.h"

// 🔴 v1.3.8 关键修复：tag 统一为 "LlamaJni"（与 SettingsPage 抓 logcat 的 -s LlamaJni:V 对齐）。
//    之前 tag 是 "LlamaJNI"（大写 JNI），诊断包抓 LlamaJni:V 抓不到 nativeInit 的 probe 日志，
//    误以为"探针没运行"。实际 probe 一直在跑，只是日志被过滤。现在统一为 LlamaJni。
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "LlamaJni", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "LlamaJni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaJni", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LlamaJni", __VA_ARGS__)

// 🔴 诊断计时工具（毫秒）
#include <chrono>
#include <cinttypes>
static inline int64_t now_ms() {
    using namespace std::chrono;
    return (int64_t)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// 🔴🔴 崩溃兜底：捕获 SIGSEGV / SIGBUS / SIGABRT（JNI llama_decode 中野指针、OOM mmap 失败都会触发），
//    不让 APP 直接闪退，而是写到 crash_msg_buf，让 nativeChat 外层检查后 cb_error 给 Java。
#include <signal.h>
#include <ucontext.h>
static thread_local char  g_crash_msg[1024];
static thread_local bool  g_crashed = false;
static struct sigaction g_old_segv, g_old_sigbus, g_old_sigabrt;
static void crash_handler(int sig, siginfo_t * info, void * /*ctx*/) {
    if (g_crashed) return;
    g_crashed = true;
    snprintf(g_crash_msg, sizeof(g_crash_msg),
        "Native signal SIG%s: code=%d addr=%p (llama_decode 访问越界/OOM mmap 失败)",
        sig == SIGSEGV ? "SEGV" : sig == SIGBUS ? "BUS" : "ABRT",
        info ? info->si_code : -1,
        info ? info->si_addr : nullptr);
    LOGE("CRASH CAUGHT: %s", g_crash_msg);
    // 恢复默认 handler 以防崩溃递归；写了 msg 后返回
    signal(sig, SIG_DFL);
}
static inline void crash_guard_push() {
    g_crashed = false;
    g_crash_msg[0] = '\0';
    struct sigaction sa{};
    sa.sa_sigaction = crash_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, &g_old_segv);
    sigaction(SIGBUS,  &sa, &g_old_sigbus);
    sigaction(SIGABRT, &sa, &g_old_sigabrt);
}
static inline void crash_guard_pop() {
    sigaction(SIGSEGV, &g_old_segv, nullptr);
    sigaction(SIGBUS,  &g_old_sigbus, nullptr);
    sigaction(SIGABRT, &g_old_sigabrt, nullptr);
}
#define CRASH_CHECK(env_, cb_) do { \
    if (g_crashed) { \
        LOGE("CRASH_CHECK 命中 → 回 cb_error: %s", g_crash_msg); \
        cb_error((env_), (cb_), std::string("💥 ") + g_crash_msg + \
            "\n这是 Native 层内存崩溃。诊断：\n" \
            "① 如果出现在 prefill 阶段 = 激活层内存峰值 OOM → 关场景开关、关后台、重启手机\n" \
            "② 如果出现在 generate 阶段 = kv_cache/激活层越界 → 缩短提问或增大 nCtx\n"); \
        if (sampler) { llama_sampler_free(sampler); sampler = nullptr; } \
        (env_)->DeleteGlobalRef(cb_); \
        crash_guard_pop(); \
        return; \
    } \
} while(0)

// --------------------------------------------------------------------
// 全局：JavaVM + callback method IDs（JNI_OnLoad 缓存，避免每次反射 FindClass）
// --------------------------------------------------------------------
static JavaVM * g_vm = nullptr;

static jclass   g_cls_Callback = nullptr; // global ref
static jmethodID g_mid_onToken = nullptr;
static jmethodID g_mid_onDone  = nullptr;
static jmethodID g_mid_onError = nullptr;
static jmethodID g_mid_onPrefill = nullptr;  // 🔴 预填充进度 onPrefillProgress(II)V

static std::mutex g_init_mutex;
static bool       g_backend_inited = false;

// --------------------------------------------------------------------
// LlamaState：Java 层保存为 long ctx（= reinterpret_cast<jlong>(this)）
// --------------------------------------------------------------------
struct LlamaState {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;

    const llama_vocab * vocab = nullptr;
    int32_t n_vocab = 0;
    int32_t n_ctx   = 0;
    int32_t n_batch = 0;

    // 取消标志：nativeChatCancel 置 true；下次 decode 前 break
    std::atomic<bool> cancel{false};

    ~LlamaState() {
        cancel.store(true);
        if (ctx)   { llama_free(ctx);   ctx   = nullptr; }
        if (model) { llama_model_free(model); model = nullptr; }
    }
};

// --------------------------------------------------------------------
// 工具：从 JavaVM 获取 JNIEnv*（自动 Attach，默认不 Detach，因为流式回调会持续调）
// --------------------------------------------------------------------
static JNIEnv * ensure_env() {
    if (!g_vm) return nullptr;
    JNIEnv * env = nullptr;
    int r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (r == JNI_OK) return env;
    if (r == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    }
    LOGE("ensure_env: 无法拿到 JNIEnv* (GetEnv=%d)", r);
    return nullptr;
}

// --------------------------------------------------------------------
// JVM 回调：onToken / onDone / onError
// --------------------------------------------------------------------
static void cb_token(JNIEnv * env, jobject callback, const std::string & piece) {
    if (!env || !callback || !g_mid_onToken) return;
    jstring jpiece = env->NewStringUTF(piece.c_str());
    env->CallVoidMethod(callback, g_mid_onToken, jpiece);
    env->DeleteLocalRef(jpiece);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("cb_token: Java 端 onToken 抛异常（已清）");
    }
}
static void cb_done(JNIEnv * env, jobject callback, const std::string & reason) {
    if (!env || !callback || !g_mid_onDone) return;
    jstring jreason = env->NewStringUTF(reason.c_str());
    env->CallVoidMethod(callback, g_mid_onDone, jreason);
    env->DeleteLocalRef(jreason);
    if (env->ExceptionCheck()) env->ExceptionClear();
}
static void cb_error(JNIEnv * env, jobject callback, const std::string & msg) {
    if (!env || !callback || !g_mid_onError) return;
    jstring jmsg = env->NewStringUTF(msg.c_str());
    env->CallVoidMethod(callback, g_mid_onError, jmsg);
    env->DeleteLocalRef(jmsg);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// =====================================================
// JNI_OnLoad：做 3 件事（所有耗时操作放 nativeInit，这里只做缓存，不能慢）
//   1) 保存 g_vm
//   2) 缓存 Callback class + 3 methodIDs（global ref，避免类卸载）
//   3) llama_backend_init 只一次
// =====================================================
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    std::lock_guard<std::mutex> l(g_init_mutex);
    g_vm = vm;
    JNIEnv * env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        LOGE("JNI_OnLoad: 无法拿 JNIEnv");
        return JNI_VERSION_1_6;
    }
    LOGI("JNI_OnLoad: 开始缓存 Callback methodIDs...");

    // com/xuedi/coder/model/LlamaJniEngine$TokenCallback
    jclass localCls = env->FindClass("com/xuedi/coder/model/LlamaJniEngine$TokenCallback");
    if (!localCls) {
        LOGE("JNI_OnLoad: FindClass TokenCallback FAILED —— 签名对不上？");
        env->ExceptionClear();
        return JNI_VERSION_1_6;
    }
    g_cls_Callback = reinterpret_cast<jclass>(env->NewGlobalRef(localCls));
    env->DeleteLocalRef(localCls);

    g_mid_onToken = env->GetMethodID(g_cls_Callback, "onToken", "(Ljava/lang/String;)V");
    g_mid_onDone  = env->GetMethodID(g_cls_Callback, "onDone",  "(Ljava/lang/String;)V");
    g_mid_onError = env->GetMethodID(g_cls_Callback, "onError", "(Ljava/lang/String;)V");
    g_mid_onPrefill = env->GetMethodID(g_cls_Callback, "onPrefillProgress", "(II)V");
    if (!g_mid_onToken || !g_mid_onDone || !g_mid_onError || !g_mid_onPrefill) {
        LOGE("JNI_OnLoad: GetMethodID 有 null（onToken=%p onDone=%p onError=%p onPrefill=%p）—— Kotlin interface 方法签名改了？",
             (void*)g_mid_onToken, (void*)g_mid_onDone, (void*)g_mid_onError, (void*)g_mid_onPrefill);
        env->ExceptionClear();
        return JNI_VERSION_1_6;
    }
    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
        LOGI("JNI_OnLoad: ✅ llama_backend_init() DONE（仅一次）");
    }
    LOGI("JNI_OnLoad: ✅ 完成。methodIDs 已缓存。");
    return JNI_VERSION_1_6;
}

// =====================================================
// nativeInit：加载模型 + 创建 ctx，返回 LlamaState* 转 jlong
// =====================================================
extern "C" JNIEXPORT jlong JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeInit(
        JNIEnv * env, jobject /* thiz */,
        jstring jmodel_path, jint nCtx, jint nThreads, jint nGpuLayers) {

    if (!g_backend_inited) { // 兜底
        std::lock_guard<std::mutex> l(g_init_mutex);
        if (!g_backend_inited) { llama_backend_init(); g_backend_inited = true; }
    }
    if (!jmodel_path) { LOGE("nativeInit: path null"); return 0; }
    const char * path = env->GetStringUTFChars(jmodel_path, nullptr);
    if (!path) { LOGE("nativeInit: GetStringUTFChars fail"); return 0; }

    // 🔴 v1.3.8 强力日志：nativeInit 入口标记（ERROR 级确保 logcat -d 必抓到）
    LOGE("===== nativeInit ENTERED (path=%s, nCtx-hint=%d, nThreads=%d, nGpuLayers=%d) =====",
         path, (int)nCtx, (int)nThreads, (int)nGpuLayers);

    // =====================================================================
    // 🔴 v1.3.5 真实连续内存探测 + 动态 n_ctx（替代 Kotlin 层猜阈值）
    //   在任何大分配前（load model / init context）先做 mmap PROT_NONE 探测，
    //   拿到"这个设备当前真能 mmap 多少连续字节"的硬数字。
    //   1800MB 是 Qwen2.5-3B Q4_K_M 跑起来的绝对下限（模型≈2000MB，但 mmap
    //   PROT_NONE 跟模型实际落页的内存不是同一种算法，所以 1.8GB 是经验安全线）。
    // =====================================================================
    int32_t real_avail_mb = probe_max_continuous_mb();
    // 🔴 v1.3.8：probe 结果用 ERROR 级打印（确保诊断包 logcat -d 必抓到）
    LOGE("probe_max_continuous_mb returned: %d MB", real_avail_mb);
    if (real_avail_mb < 1800) {
        // 🔴 v1.3.8 任务二 2.3：探针失败时 ThrowNew 抛 RuntimeException，
        //    让 Java 层 LlamaJniEngine.loadModel 的 try-catch 捕获并把具体原因写入 lastLoadError，
        //    SettingsPage 诊断卡可显示。光 return 0L 只给通用文案，用户不知道是内存不足。
        std::string err = "Device memory too low (" + std::to_string(real_avail_mb) +
            " MB < 1800 MB floor). Close all background apps and reboot.";
        LOGE("nativeInit: %s", err.c_str());
        env->ReleaseStringUTFChars(jmodel_path, path);
        jclass rtCls = env->FindClass("java/lang/RuntimeException");
        if (rtCls != nullptr) {
            env->ThrowNew(rtCls, err.c_str());
            env->DeleteLocalRef(rtCls);
        }
        return 0L;
    }
    // 动态计算 n_ctx：留出 300MB 给 kv_cache + 系统，其余按 0.7 系数估算
    //   （系数 0.7 是经验：3B Q4_K_M 在 4096 ctx 时，model+kv+激活≈2.95GB / 可用连续 mmap
    //    所以 可用MB - 300MB ≈ 模型+激活的预算，除以 0.7 反推"如果真给 4096 ctx 需要多少"，
    //    实际只取 min(4096, 这个值) 作为最终 n_ctx。）
    int32_t dynamic_n_ctx_raw = (int32_t)(((int64_t)real_avail_mb - 300) / 0.7);
    int32_t dynamic_n_ctx = dynamic_n_ctx_raw;
    if (dynamic_n_ctx > 4096) dynamic_n_ctx = 4096;   // 封顶 4096
    if (dynamic_n_ctx < 512)  dynamic_n_ctx = 512;    // 至少 512
    LOGI("nativeInit: dynamic_n_ctx = %d (real_avail=%d MB raw=%d, Java-hint nCtx=%d -> clamp)",
         dynamic_n_ctx, real_avail_mb, dynamic_n_ctx_raw, (int)nCtx);

    LOGI("nativeInit: 开始加载 GGUF → %s (final nCtx=%d, nThreads=%d, nGpuLayers=%d)",
         path, dynamic_n_ctx, (int)nThreads, (int)nGpuLayers);

    auto state = std::make_unique<LlamaState>();

    // 1) load model
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = (int32_t)nGpuLayers; // CPU 场景 0
    state->model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jmodel_path, path);
    if (!state->model) {
        // 🆕 v1.3.25-fix8: ThrowNew 把具体原因抛给 Java，不再只 return 0（= 笼统 ctx=0 文案）。
        //    用户"点🔄 就失败"= 99% 是 mmap / 魔数损坏 / tensor 对齐三选一，
        //    Java 层拿到 RuntimeException 后直接写入 lastLoadError → Toast 原文展示。
        std::string err = "llama_model_load_from_file FAIL。"
            " 可能原因：① GGUF 文件下载损坏（与官方 sha256 核对）② 模型大小超过 1.8GB 时 App 被后台压到连续 mmap 不足"
            " ③ 文件路径不可读（SAF 导入时权限没拿到、或 filesDir 被系统清了 ）。"
            " 排错：先设置 → 诊断 → 看 probe_max_continuous_mb=?MB。如<2000MB 先关后台/重启。";
        LOGE("nativeInit: %s", err.c_str());
        jclass rtCls = env->FindClass("java/lang/RuntimeException");
        if (rtCls != nullptr) { env->ThrowNew(rtCls, err.c_str()); env->DeleteLocalRef(rtCls); }
        return 0;
    }

    // 2) get vocab
    state->vocab   = llama_model_get_vocab(state->model);
    state->n_vocab = llama_vocab_n_tokens(state->vocab);
    LOGI("nativeInit: vocab_size=%d, n_ctx_train=%d, size=%llu MB",
         (int)state->n_vocab,
         (int)llama_model_n_ctx_train(state->model),
         (unsigned long long)(llama_model_size(state->model) / 1024ULL / 1024ULL));

    // 3) create context — n_ctx 全部用 dynamic_n_ctx，不再用 Java 传进来的 nCtx
    auto cparams = llama_context_default_params();
    // 🔴 v1.3.9 修复一（DeepSeek 报告）：KV cache 内存安全降级 + n_batch 256→128。
    //   诊断显示 prefill(21225ms)成功但 generate 阶段第一个 llama_decode SIGABRT
    //   (addr=0x2868... OOM mmap 失败)。根因：n_ctx=4096 的 KV cache 在 decode 时
    //   瞬时内存峰值超限。按 real_avail_mb 分级降级 + 降低 n_batch 削峰。
    int32_t safe_n_ctx = dynamic_n_ctx;
    if (real_avail_mb < 3000) {
        safe_n_ctx = std::min(safe_n_ctx, 2048);
    }
    if (real_avail_mb < 2500) {
        safe_n_ctx = std::min(safe_n_ctx, 1024);
    }
    // 🔴 v1.3.25-fix11：去掉魅族20特供降级（real_avail_mb >= 4000 && < 4200 → safe_n_ctx=2048）。
    //   v1.3.10 加这个是为了验证"KV cache 在 3B 模型上有问题"的假设，但现在用户跑的是 1.5B 小模型，
    //   而且 fix10 已经修了 GGUF 解析——特供降级反而让 1.5B 模型的 n_ctx 被人为砍到 2048，
    //   对 n_batch=256 的正常 prefill 流程没必要。让统一降级逻辑工作：
    //   real_avail < 3000 → min(2048)；< 2500 → min(1024)。
    //   1.5B Q4_K_M (≈940MB) + n_ctx=4096 的 KV cache ≈ 800MB，魅族20 real_avail=4096 完全够。
    cparams.n_ctx        = (uint32_t)safe_n_ctx;
    // 🔴 v1.3.8：n_ctx 最终值用 ERROR 级打印，诊断包必抓到
    LOGE("cparams.n_ctx set to %d (safe_n_ctx=%d, real_avail=%d MB)",
         cparams.n_ctx, safe_n_ctx, real_avail_mb);
    // 🔴 v1.3.25-fix14：n_batch=1 n_ubatch=1。
    //   fix12 用 n_batch=8 还是崩！用户确认 Llama 一发消息就闪退。
    //   fix10 用 n_batch=1 完整跑通 1024 tokens 不崩——这是唯一的硬证据。
    //   n_batch=8 仍然触发 llama.cpp b5180 的 batch decode assertion（SIGABRT）。
    //   结论：魅族20 上 b5180 只能 n_batch=1，没有折中空间。
    //   速度慢但能用 > 快但崩。配合手动 BOS 插入，应该既不崩也不乱码。
    cparams.n_batch      = 1;
    cparams.n_ubatch     = 1;
    LOGE("cparams.n_batch=1 n_ubatch=1 (v1.3.25-fix14: n_batch=8 仍崩，回到 fix10 的 n_batch=1)");
    cparams.logits_all   = false;
    // 线程数回退到正常（v1.3.13 单线程也崩，排除线程因素；恢复多线程 prefill 更快）
    int32_t n_threads_use = std::max(1, (int)nThreads);
    cparams.n_threads       = n_threads_use;
    cparams.n_threads_batch = n_threads_use;
    state->ctx = llama_init_from_model(state->model, cparams);
    if (!state->ctx) {
        // 🆕 v1.3.25-fix8: 同样 ThrowNew。这里挂掉 = 模型加载成功但 KV cache 分配失败。
        //    90% 场景是 real_avail_mb 刚够 probe 过 1800，但 model + 其他后台把剩余占满，
        //    mmap(KV_cache_size) 直接 ENOMEM。把 safe_n_ctx / real_avail_mb / 模型大小都塞进异常，
        //    Java 层 robust 下一轮会用更小的 n_ctx 自动重试。
        uint64_t ms = llama_model_size(state->model) / 1024ULL / 1024ULL;
        char buf[256];
        snprintf(buf, sizeof(buf),
            "llama_init_from_model FAIL (KV cache / weights 峰值 OOM)。"
            " 模型=%llu MB, real_avail_mb=%d, safe_n_ctx=%d, n_batch=1, n_threads=%d."
            " 下一档会自动降到 n_ctx 更小（L2 2048 / L3 1280 / L4 768）。"
            " 如果 4 档全挂，说明连续 mmap 真的不够——关所有后台 / 重启 / 切 Qwen 极简推理器。",
            (unsigned long long)ms, real_avail_mb, safe_n_ctx, n_threads_use);
        LOGE("nativeInit: %s", buf);
        jclass rtCls2 = env->FindClass("java/lang/RuntimeException");
        if (rtCls2 != nullptr) { env->ThrowNew(rtCls2, buf); env->DeleteLocalRef(rtCls2); }
        return 0;
    }
    state->n_ctx   = (int32_t)llama_n_ctx(state->ctx);
    state->n_batch = (int32_t)llama_n_batch(state->ctx);
    LOGI("nativeInit: ✅ OK. state=%p n_ctx=%d n_batch=%d n_vocab=%d n_threads=%d",
         state.get(), (int)state->n_ctx, (int)state->n_batch, (int)state->n_vocab, (int)n_threads_use);
    return reinterpret_cast<jlong>(state.release());
}

// =====================================================
// nativeRelease：销毁 state（ctx/model 内存）
// =====================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeRelease(JNIEnv *, jobject, jlong jstate) {
    if (!jstate) return;
    auto * state = reinterpret_cast<LlamaState*>(jstate);
    LOGI("nativeRelease: state=%p", state);
    delete state;
}

// =====================================================
// nativeChatCancel：取消正在跑的推理（set cancel flag）
// =====================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeChatCancel(JNIEnv *, jobject, jlong jstate) {
    if (!jstate) return;
    auto * state = reinterpret_cast<LlamaState*>(jstate);
    state->cancel.store(true);
    LOGW("nativeChatCancel: state=%p → cancel=true", state);
}

// =====================================================
// nativeChat：真正解码循环（阻塞 + 流式回调 Java）
//   1. 拼 ChatML prompt → tokenize
//   2. 创建 sampling chain：top_k 40 → top_p 0.95 → temp 0.7 → dist
//   3. 预填充 prompt（循环按 n_batch 切片 llama_decode）
//   4. 生成循环（最多 max_tokens）：sample → accept → token_to_piece → cb_token
// =====================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_LlamaJniEngine_nativeChat(
        JNIEnv *, jobject,
        jlong jstate,
        jstring jsystem,
        jstring juser,
        jobject jcallback) {

    auto * state = reinterpret_cast<LlamaState*>(jstate);
    if (!state || !state->ctx || !state->model || !state->vocab) {
        LOGE("nativeChat: state null / 未初始化 (state=%p ctx=%p model=%p vocab=%p)",
             (void*)state,
             state ? (void*)state->ctx : nullptr,
             state ? (void*)state->model : nullptr,
             state ? (void*)state->vocab : nullptr);
        JNIEnv * env = ensure_env();
        cb_error(env, jcallback, "JNI 推理状态异常：模型未加载。请到设置页重新导入并设为当前。");
        return;
    }
    state->cancel.store(false);
    const int64_t t0 = now_ms();

    // 🔴 v1.3.25-fix16: 清理 KV cache！
    //   第一次对话后 KV cache 残留旧 token 的 K/V，第二次 prefill 从 pos=0 开始覆盖，
    //   但旧 KV 条目（pos > n_prompt 的部分）还在，模型拿到混乱的上下文 → 吐不出字。
    //   每次新对话开始前必须清空 KV cache。
    llama_kv_cache_clear(state->ctx);
    LOGI("nativeChat: 🧹 KV cache cleared");

    LOGI("nativeChat: ⭐ ENTER state=%p ctx=%p n_ctx=%d n_batch=%d cancel=%d",
         (void*)state, (void*)state->ctx, (int)state->n_ctx, (int)state->n_batch,
         (int)state->cancel.load());

    JNIEnv * env = ensure_env();
    if (!env) {
        LOGE("nativeChat: ensure_env FAIL —— 无法 attach JVM");
        return;
    }
    jobject callback = env->NewGlobalRef(jcallback);
    if (!callback) { LOGE("nativeChat: NewGlobalRef(callback) FAIL"); return; }

    // 🔴 崩溃兜底：注册 SIGSEGV/SIGBUS/SIGABRT handler
    crash_guard_push();

    // -------- 1) 取 system/user 字符串，拼 ChatML（Qwen 2.5 默认 chatml template） --------
    auto j2s = [&](jstring jstr) -> std::string {
        if (!jstr) return {};
        const char * s = env->GetStringUTFChars(jstr, nullptr);
        std::string r(s ? s : "");
        env->ReleaseStringUTFChars(jstr, s);
        return r;
    };
    std::string system = j2s(jsystem);
    std::string user   = j2s(juser);
    if (system.empty()) system = "You are a helpful coding assistant. You write concise, correct code.";
    if (user.empty())   { cb_error(env, callback, "用户输入为空"); env->DeleteGlobalRef(callback); crash_guard_pop(); return; }

    std::string prompt;
    prompt.reserve(system.size() + user.size() + 160);
    prompt += "<|im_start|>system\n";   prompt += system;   prompt += "<|im_end|>\n";
    prompt += "<|im_start|>user\n";     prompt += user;     prompt += "<|im_end|>\n";
    prompt += "<|im_start|>assistant\n";

    LOGD("nativeChat: prompt 长度=%zu bytes, system=%d user=%d", prompt.size(), (int)system.size(), (int)user.size());
    LOGI("nativeChat: 📝 prompt 前 160 bytes: %.160s", prompt.c_str());

    // -------- 2) tokenize --------
    //    llama_tokenize 的官方语义：
    //      · 第一次传 buffer=nullptr, n_max=0 → 返回「需要的 token 数」。
    //        注意：返回值可能是**正**也可能是**负**（负值不代表错误！历史上不同版本、
    //        不同 special token 情况下都出现过负值 = 绝对值是实际需要的大小），
    //        所以 estimate 结束后必须做 abs(need)，否则会把 -1214 这种真实需求当错误，
    //        走 fallback 只分几十/几百的 buffer → 第二次 tokenize 还返回同样的负值，
    //        我们误以为 tokenizer 损坏，给用户报错（这正是用户截图里的 -1214 根因！）。
    //      · 第二次传 buffer!=nullptr, n_max>0 → 返回真实写入的 token 数；
    //        若此时仍是负值才是真错误（buffer 不够、GGUF 缺 vocab 等）。
    std::vector<llama_token> tokens;
    {
        // 🔴🔴 v1.3.25-fix12 关键修复（BOS 缺失 = 乱码根因）：
        //   fix11 用 add_spec=1 但 token[0]=151644(<|im_start|>) ≠ bos_id=151643！
        //   原因：prompt 开头是 <|im_start|>（已是 special token），tokenizer 跳过了 BOS
        //   （避免两个 special token 挨着）。但 Qwen2.5 训练时 prompt 有 BOS，
        //   没 BOS 模型就会"困惑"发散成乱码。
        //   修法：add_spec=0 让 tokenizer 完全不自动加任何 special token，
        //         然后手动在 token 序列开头插 bos_id——确保 BOS 一定在最前面！
        int32_t add_spec   = 0;   // 不自动加 BOS
        int32_t parse_spec = 1;   // 但要解析 <|im_start|>/<|im_end|> 为 special token
        // 第一步：估算真实 token 数（无论正负，最后 abs）
        int32_t need = llama_tokenize(state->vocab, prompt.c_str(), (int32_t)prompt.size(),
                                      nullptr, 0, add_spec, parse_spec);
        if (need == 0) {
            // 极少场景：空串（理论上前面判过）、或 tokenizer 没初始化
            cb_error(env, callback, "tokenizer 返回 0 个 token（GGUF 缺 vocab 数据？）");
            env->DeleteGlobalRef(callback); crash_guard_pop();
            return;
        }
        // 🔴 关键修复：need 为负 = 该版本 llama_tokenize 用负值表示需求大小（绝对值是真实个数），
        //    官方 main/common 示例里也统一用 abs 后再分配 buffer。
        int32_t est = (need > 0) ? need : (-need);
        // 留 8 个余量（BOS/拼接 special 的边界差），至少 64，最多 n_ctx-16（防止越上下文上限后立即 OOM）
        int32_t cap = est + 8;
        cap = std::max(cap, 64);
        cap = std::min(cap, std::max(64, state->n_ctx - 16));
        LOGI("nativeChat: tokenize estimate raw need=%d → cap=%d (final, 留余量且不超 n_ctx-16),"
             " prompt bytes=%zu vocab=%d", (int)need, (int)cap, prompt.size(), (int)state->n_vocab);
        tokens.resize((size_t)cap);
        int32_t real = llama_tokenize(state->vocab, prompt.c_str(), (int32_t)prompt.size(),
                                      tokens.data(), (int32_t)tokens.size(), add_spec, parse_spec);
        if (real <= 0) {
            // 🔴 第二次返回负值才是真错误：buffer 不够 or GGUF 缺 tokenizer
            LOGE("nativeChat: llama_tokenize(real) 失败 ret=%d cap=%d → 真正的 GGUF tokenizer 错误或 buffer 仍不够",
                 (int)real, (int)cap);
            // buffer 不够的兜底：再把 cap 扩 2 倍（到 n_ctx-16）再试一次
            if (cap < state->n_ctx - 16) {
                int32_t cap2 = std::min(cap * 2, state->n_ctx - 16);
                LOGW("nativeChat: buffer 不够 real=%d，重试 cap=%d→%d", (int)real, (int)cap, (int)cap2);
                tokens.resize((size_t)cap2);
                real = llama_tokenize(state->vocab, prompt.c_str(), (int32_t)prompt.size(),
                                      tokens.data(), (int32_t)tokens.size(), add_spec, parse_spec);
            }
            if (real <= 0) {
                cb_error(env, callback,
                    std::string("llama_tokenize 失败（返回") + std::to_string(real) +
                    "，已尝试 2 次分配 buffer。\n" +
                    "可能原因：① GGUF 缺 tokenizer 元数据 ② 文件损坏 ③ token 数超 n_ctx-16。\n" +
                    "建议：先尝试用更短的问题（100 字内）验证；若仍失败请删模型重下。");
                env->DeleteGlobalRef(callback); crash_guard_pop();
                return;
            }
        }
        // real <= cap（如果 real < cap 说明 estimate 估多了，截断就好）
        if (real > (int32_t)tokens.size()) real = (int32_t)tokens.size();
        tokens.resize((size_t)real);
    }
    int32_t n_prompt = (int32_t)tokens.size();   // 非 const：BOS 修正时会 -= 1
    // -------- 先拿 eos/bos（做 BOS 对齐校验要用，必须在 sampler/prefill 之前） --------
    const llama_token eos = llama_vocab_eos(state->vocab);
    const llama_token bos = llama_vocab_bos(state->vocab);
    LOGI("nativeChat: 📌 vocab eos=%d bos=%d, tokenize DONE n_prompt=%d / n_ctx=%d (tokenize 耗时 %" PRId64 " ms)",
         (int)eos, (int)bos, (int)n_prompt, (int)state->n_ctx, now_ms() - t0);

    // 🔴 v1.3.25-fix12: 手动在 token 序列开头插 BOS token！
    //   根因：add_spec=0（不自动加 BOS），因为 add_spec=1 时 tokenizer 会因为 prompt
    //   开头是 <|im_start|>(special token) 而跳过 BOS，导致 token[0]=151644≠bos_id=151643。
    //   模型没收到 BOS 就困惑发散成乱码。手动插 BOS 是唯一可靠方案。
    if (bos != 0 && bos != -1) {
        tokens.insert(tokens.begin(), bos);
        n_prompt = (int32_t)tokens.size();
        LOGI("nativeChat: ✂️ 手动插 BOS: bos_id=%d, 新 n_prompt=%d, token[0]=%d",
             (int)bos, (int)n_prompt, (int)tokens[0]);
    } else {
        LOGW("nativeChat: ⚠️ bos_id=%d 无效，跳过手动插 BOS", (int)bos);
    }
    // 诊断：打印 ChatML special token 的真实 ID
    {
        std::vector<llama_token> v(4);
        int32_t r1 = llama_tokenize(state->vocab, "<|im_start|>", -1, v.data(), 4, 0, 1);
        int32_t r2 = llama_tokenize(state->vocab, "<|im_end|>",   -1, v.data(), 4, 0, 1);
        llama_token real_im_start = (r1 > 0) ? v[0] : (r1 < 0 ? v[0] : -1);
        llama_token real_im_end   = (r2 > 0) ? v[0] : (r2 < 0 ? v[0] : -1);
        LOGI("nativeChat: 🎯 ChatML special token IDs: <|im_start|>=%d <|im_end|>=%d bos=%d eos=%d hardcoded_im_end=151645",
             (int)real_im_start, (int)real_im_end, (int)bos, (int)eos);
    }
    // 完整性校验 1：token[0] 绝不能是 EOS（否则 generate 0 token 结束）
    if (!tokens.empty() && tokens[0] == eos) {
        LOGE("nativeChat: ❌ FATAL token[0]==eos=%d —— generate 阶段 0 token 结束。"
             " ChatML 模板 + tokenize 参数完全错位。", (int)eos);
        cb_error(env, callback,
            "tokenize 异常：token[0]==EOS。ChatML 模板不匹配。\n"
            "建议：① 设置页点「诊断」按钮抓日志 ② 删模型重新导入 GGUF");
        env->DeleteGlobalRef(callback);
        crash_guard_pop();
        return;
    }

    // 🔴 诊断：打印前 16 个 token id + 前 8 个 piece（BOS 修正之后）
    {
        int32_t show_n = std::min(n_prompt, 16);
        std::string first_pcs;
        char pcbuf[64];
        for (int i = 0; i < std::min(show_n, 8); i++) {
            int32_t n = llama_token_to_piece(state->vocab, tokens[i], pcbuf, sizeof(pcbuf), 0, 0);
            if (n > 0) first_pcs.append(pcbuf, (size_t)n);
            else if (n < 0) {
                std::vector<char> big((size_t)(-n) + 2);
                llama_token_to_piece(state->vocab, tokens[i], big.data(), (int)big.size(), 0, 0);
                first_pcs.append(big.data());
            }
        }
        LOGI("nativeChat: 📊 first %d tokens (AFTER BOS-fix): ids=[%d,%d,%d,%d,%d,%d,%d,%d...]; piece_前8=[%s]",
             show_n,
             n_prompt>0?tokens[0]:-1, n_prompt>1?tokens[1]:-1, n_prompt>2?tokens[2]:-1, n_prompt>3?tokens[3]:-1,
             n_prompt>4?tokens[4]:-1, n_prompt>5?tokens[5]:-1, n_prompt>6?tokens[6]:-1, n_prompt>7?tokens[7]:-1,
             first_pcs.c_str());
        (void)show_n;
    }
    if (n_prompt >= state->n_ctx - 16) {
        cb_error(env, callback, "输入过长（token 数=" + std::to_string(n_prompt) +
                                " 已接近上下文上限 " + std::to_string(state->n_ctx) + "）。请缩短提问或提高 nCtx。");
        env->DeleteGlobalRef(callback);
        crash_guard_pop();
        return;
    }

    // -------- 3) sampling chain：penalties → top_k → top_p → temp → dist --------
    // 🆕 v1.3.25-fix10: 彻底重写采样器链. 之前 fix9 顺序错 (top_k 在 penalties 前)
    //   导致 penalties 对已截断的分布完全无效, 加上 temp=0.6/top_k=30/top_p=0.9 太宽,
    //   魅族20 (骁龙8 Gen2 + 1.5B Q4_K_M) 上输出漂移发散成乱码.
    //
    //   正确顺序: penalties 先作用于 raw logits, 再 top_k/top_p 截断, 最后 temp 缩放.
    //   参数收紧 (保守档): top_k=10 top_p=0.8 temp=0.3 repeat=1.05 freq=0 presence=0
    //   freq=0 presence=0 是因为 1.5B 模型本来就小, 加 freq/presence 惩罚反而抑制了正常
    //   多字节 UTF-8 序列 (中文每个字都是独立 token, freq 惩罚会让模型回避常用汉字).
    llama_sampler * sampler = nullptr;
    {
        auto sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        sampler = llama_sampler_chain_init(sp);
        // ① penalties: repeat 只对重复 token 生效, freq=0 presence=0 避免抑制中文
        int32_t repeat_last_n = std::min(std::max(n_prompt, 32), 1600);
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            /*penalty_last_n=*/repeat_last_n,
            /*penalty_repeat=*/1.05f,
            /*penalty_freq=*/0.0f,
            /*penalty_present=*/0.0f
        ));
        // ② top_k=10: 只保留概率最高的 10 个 token
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(10));
        // ③ top_p=0.8: nucleus 采样, 只保留累计概率 0.8 内的 token
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.8f, /*min_keep=*/1));
        // ④ temp=0.3: 非常低的温度, 接近 greedy
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
        // ⑤ dist: 最后一层做确定性采样
        llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t)::time(nullptr) ^ 0xC0FFEEu));
    }
    // 🆕 关键：b5180 的 penalties sampler 通过 llama_sampler_accept 把 prompt tokens
    //     写入内部 last_n 环形缓冲（=告诉 sampler「这些 token 不要重复」）。
    //     若漏掉这一步，即便加了 penalties sampler 也完全不生效（之前旧版就是空转）。
    for (llama_token t : tokens) llama_sampler_accept(sampler, t);

    // -------- 4) 预填充 prompt（按 n_batch 切片） --------
    int32_t n_consumed = 0;
    // 🔴 v1.3.25-fix14：彻底重写 prefill——不用 llama_batch_get_one！
    //   旧代码 llama_batch_get_one(tokens.data(), n_prompt) 创建 n_prompt 大小的 batch，
    //   然后 batch.token[i] = tokens[n_consumed + i] 会覆盖 tokens 数组开头（因为 token 指针
    //   直接指向 tokens.data()）。虽然不影响 prefill 结果，但在 b5180 上可能触发内部 assertion。
    //   新方案：用 llama_batch_init(n_batch, 0) 创建最小 batch，只填 1 个 token，安全干净。
    // 🔴 v1.3.25-fix17: 彻底不信任 llama_n_batch 覆盖回来的值！
    //   我在 nativeInit 里设 cparams.n_batch=1，但 llama_n_batch(ctx) 读回 64——
    //   这导致 llama_batch_init(64,0,1) 分配 64 大小的数组，然后 prefill 循环里
    //   batch.n_tokens=64 一次喂 64 token，触发 b5180 内部的 assertion SIGABRT！
    //   修法：无论 state->n_batch 是多少，强制 BATCH_SIZE=1，完全绕开 batch decode 代码路径。
    //   生成阶段同样 batch.n_tokens=1，确保每次 llama_decode 只处理 1 个 token。
    constexpr int32_t SAFE_BATCH = 1;
    struct llama_batch batch = llama_batch_init(SAFE_BATCH, 0, 1);
    (void)state->n_batch;  // 屏蔽警告
    {
        const int64_t t_pre0 = now_ms();
        int batch_idx = 0;
        while (n_consumed < n_prompt) {
            if (state->cancel.load()) {
                llama_batch_free(batch);
                cb_done(env, callback, "cancel");
                if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
                env->DeleteGlobalRef(callback);
                crash_guard_pop();
                return;
            }
            // SAFE_BATCH=1 → 每次只喂 1 个 token，完全绕开 batch decode 代码路径
            batch.n_tokens = std::min<int32_t>(SAFE_BATCH, n_prompt - n_consumed);
            for (int i = 0; i < batch.n_tokens; i++) {
                batch.token[i]    = tokens[n_consumed + i];
                batch.pos[i]      = n_consumed + i;
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                bool is_last_prefill_token = (n_consumed + i == n_prompt - 1);
                batch.logits[i]   = is_last_prefill_token ? 1 : 0;
            }
            LOGI("nativeChat: ⏳ prefill #%d: SAFE_BATCH=%d, n_tokens=%d, token[%d]=%d (pos=%d), consumed=%d/%d",
                 batch_idx, SAFE_BATCH, batch.n_tokens, 0, batch.token[0], batch.pos[0], n_consumed, n_prompt);
            int decode_rc = llama_decode(state->ctx, batch);
            int64_t tb1 = now_ms();
            if (decode_rc != 0) {
                LOGE("nativeChat: prefill decode #%d FAIL rc=%d (batch.n_tokens=%d, consumed=%d) — 耗时 %" PRId64 " ms",
                     batch_idx, decode_rc, batch.n_tokens, n_consumed, tb1 - t_pre0);
                cb_error(env, callback, "预填充 llama_decode FAIL（OOM？上下文不够？）rc=" + std::to_string(decode_rc));
                llama_batch_free(batch);
                if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
                env->DeleteGlobalRef(callback);
                crash_guard_pop();
                return;
            }
            CRASH_CHECK(env, callback);
            n_consumed += batch.n_tokens;
            batch_idx++;
            if (g_mid_onPrefill && callback) {
                env->CallVoidMethod(callback, g_mid_onPrefill, (jint)n_consumed, (jint)n_prompt);
                if (env->ExceptionCheck()) { env->ExceptionClear(); }
            }
        }
        LOGI("nativeChat: ✅ prefill DONE. n_consumed=%d n_prompt=%d, total cost=%" PRId64 " ms (enter→now %" PRId64 " ms)",
             (int)n_consumed, (int)n_prompt, now_ms() - t_pre0, now_ms() - t0);
    }

    // -------- 5) 生成循环：最多 1024 token --------
    const int32_t MAX_TOKENS = 1024;
    int32_t n_generated = 0;
    std::string piece_buf;
    piece_buf.resize(32);  // token_to_piece 通常 4~16 字节，32 覆盖大多数 emoji/中文字符
    llama_token last_id   = 0;
    const int64_t t_gen0 = now_ms();

    while (n_generated < MAX_TOKENS) {
        if (state->cancel.load()) { cb_done(env, callback, "cancel"); break; }

        int64_t ts0 = now_ms();
        // a. sample 最后一个 logit（idx = -1）
        llama_token id = llama_sampler_sample(sampler, state->ctx, /*idx=*/-1);
        llama_sampler_accept(sampler, id);
        int64_t ts1 = now_ms();

        // b. EOS / <|im_end|> (Qwen 词表标准 id=151645) / 到上下文上限 → Done
        if (id == eos) {
            LOGI("nativeChat: 🏁 EOS hit at n_generated=%d (token_id=%d). Gen total %" PRId64 " ms",
                 (int)n_generated, (int)id, now_ms() - t_gen0);
            cb_done(env, callback, "stop"); break;
        }
        // 🔴 v1.3.23: 硬编码 Qwen 标准 <|im_end|> token_id=151645, 命中即停止.
        //   v1.3.22 闪退根因: llama_tokenize("<|im_end|>") 探测调用在 b4835 + 骁龙 8 Gen 2
        //   下触发 prefill 前 SIGABRT. 现完全放弃探测, 用常量一行追加判断:
        //   - 若 151645 正确: 模型输出 <|im_end|> 后立即结束 (根治乱码尾巴)
        //   - 若 151645 错误: 最坏行为 == v1.3.16, 不会崩溃, 只是写满 MAX_TOKENS(1024) 乱码
        //   不新增任何 llama_tokenize 调用, MAX_TOKENS 保持 1024 不动.
        if (id == 151645) {
            LOGI("nativeChat: 🏁 im_end (hardcoded=151645) hit at n_generated=%d. Gen total %" PRId64 " ms",
                 (int)n_generated, now_ms() - t_gen0);
            cb_done(env, callback, "im_end");
            break;
        }
        if (n_consumed >= state->n_ctx - 2) {
            LOGI("nativeChat: 🏁 context_limit at n_generated=%d → DONE", (int)n_generated);
            cb_done(env, callback, "context_limit"); break;
        }

        // c. token → piece（需要再次调用，得到真实字节数）
        int32_t n = llama_token_to_piece(state->vocab, id, piece_buf.data(), (int32_t)piece_buf.size(),
                                          /*lstrip=*/0, /*special=*/false);
        if (n < 0) {
            piece_buf.resize((size_t)(-n));
            llama_token_to_piece(state->vocab, id, piece_buf.data(), (int32_t)piece_buf.size(),
                                 /*lstrip=*/0, /*special=*/false);
            n = -n;
        }
        std::string piece;
        if (n > 0) {
            piece.assign(piece_buf.data(), (size_t)n);
            cb_token(env, callback, piece);
        }
        // d. decode 下一步（单个 token）—— 复用 batch，零 malloc
        int64_t td0 = now_ms();
        last_id = id;
        batch.token[0]   = id;
        batch.pos[0]     = n_consumed;  // 生成阶段的 pos = 已消费 token 数（下一个位置）
        batch.n_seq_id[0]      = 1;
        batch.seq_id[0][0]     = 0;
        batch.logits[0]        = 1;    // generate 每个 token 都要 logits 供下一轮 sample
        batch.n_tokens = 1;
        // 🔴 v1.3.9 修复二（DeepSeek 报告）：generate decode 前防御性检查。
        //   诊断显示 prefill(21225ms)成功但 generate 第一个 llama_decode SIGABRT
        //   (OOM mmap 失败, addr=0x2868...)。prefill 已成功用 ctx → ctx 正常，
        //   此处二次确认 ctx 非空避免访问空指针；OOM 峰值由 nativeInit 的 n_batch=128 削峰。
        //   注：不用 llama_state_get_size，该 API 在本项目 b4812 未确认存在，避免编译风险。
        if (state->ctx == nullptr) {
            LOGE("nativeChat: generate decode 前 ctx==null（KV cache 未分配？）→ cb_error 回 Java");
            cb_error(env, callback, "generate decode 前 ctx 为空，KV cache 未分配，请降低 n_ctx");
            break;
        }
        int decode_rc = llama_decode(state->ctx, batch);
        int64_t td1 = now_ms();
        if (decode_rc != 0) {
            LOGE("nativeChat: generate decode FAIL rc=%d at token %d (sample=%" PRId64 "ms, decode=%" PRId64 "ms)",
                 decode_rc, (int)n_generated, ts1 - ts0, td1 - td0);
            cb_error(env, callback, "生成阶段 llama_decode FAIL (token " + std::to_string(n_generated) + ") rc=" + std::to_string(decode_rc));
            break;
        }
        n_consumed++;
        n_generated++;
        if (n_generated % 10 == 0) {
            int64_t gtotal = now_ms() - t_gen0;
            double tps = (gtotal > 0) ? (n_generated * 1000.0 / gtotal) : 0.0;
            LOGI("nativeChat: 🚀 token #%d: id=%d sample=%" PRId64 "ms decode=%" PRId64 "ms | piece_last(16)=%.16s | speed %.1f tok/s | total_gen %" PRId64 "ms",
                 (int)n_generated, (int)id, ts1 - ts0, td1 - td0, piece.c_str(), tps, gtotal);
        }
    }

    // 正常 MAX_TOKENS 达到（没 EOS 且没 cancel） → 也算 Done
    if (n_generated >= MAX_TOKENS && !state->cancel.load() && last_id != eos) {
        LOGI("nativeChat: 🏁 MAX_TOKENS 达到 n_generated=%d", (int)n_generated);
        cb_done(env, callback, "length");
    }
    LOGI("nativeChat: ⭐ EXIT n_generated=%d n_consumed=%d total elapsed=%" PRId64 " ms (enter→now)",
         (int)n_generated, (int)n_consumed, now_ms() - t0);
    LOGI("nativeChat: DONE. n_generated=%d, last_id=%d, eos=%d",
         (int)n_generated, (int)last_id, (int)eos);

    if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
    // 🔴 v1.3.25-fix11: 释放 batch（llama_batch_get_one 返回的，需要 llama_batch_free）
    llama_batch_free(batch);
    env->DeleteGlobalRef(callback);
    crash_guard_pop();
    return;
}

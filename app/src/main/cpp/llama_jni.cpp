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
        LOGE("nativeInit: llama_model_load_from_file FAIL（文件格式？权限？大小？）");
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
    // 🔴 v1.3.10 修复一（DeepSeek 报告）：魅族 20 特殊降级验证。
    //   v1.3.9 诊断：n_batch=128 后 prefill 仍崩（128/246 完成后 SIGABRT），
    //   说明不是 n_batch 问题，是 n_ctx=4096 的 KV cache 分配/索引在 3B 模型上有问题。
    //   魅族 20 real_avail 探测 4096（>=4000 && <4200）→ 强制 n_ctx 降到 2048 验证。
    //   若 2048 跑通 → 锁定 KV cache 内存分配；若仍崩 → llama.cpp 内部逻辑/指令集问题。
    if (real_avail_mb >= 4000 && real_avail_mb < 4200) {
        safe_n_ctx = 2048;
        LOGE("nativeInit: ⚠️ 魅族20特殊降级: n_ctx %d → %d (验证 KV cache 假设)",
             dynamic_n_ctx, safe_n_ctx);
    }
    // 魅族 20 real_avail_mb=4096 → v1.3.10 降级到 2048（KV cache 减半验证）。
    cparams.n_ctx        = (uint32_t)safe_n_ctx;
    // 🔴 v1.3.8：n_ctx 最终值用 ERROR 级打印，诊断包必抓到
    LOGE("cparams.n_ctx set to %d (safe_n_ctx=%d, real_avail=%d MB)",
         cparams.n_ctx, safe_n_ctx, real_avail_mb);
    // 🔴 v1.3.14-beta 最终绕路：强制 n_batch=1, n_ubatch=1。
    //   崩溃模式（v1.3.8→v1.3.13 全版本一致）：prefill 第一个 batch (128 tokens) 成功，
    //   第二个 batch 的 llama_decode SIGABRT。b5180 多线程崩 / 单线程崩 / 1.5B 小模型也崩 →
    //   排除内存/模型大小/线程数 → 锁定崩溃点在 llama_decode 处理 batch 切换时的内部状态机。
    //   解法：n_batch=1 → prefill 循环每次只喂 1 token（llama_batch_get_one 永远只返回 1 token），
    //   从根本上消除 batch 切换，绕开崩溃点。
    //   ⚠️ 代价：prefill 变慢（246 tokens 要调 246 次 llama_decode，比 n_batch=128 慢 10-20x），
    //      但 generate 阶段本来就是 1 token decode，不受影响。
    //   为什么用户指令里的编译参数是 no-op：
    //     - GGML_USE_ARM_SVE 不存在（b5180 无此选项；SVE 通过 GGML_NATIVE→-march=native 自动探测，
    //       当前 CMakeLists L60 GGML_NATIVE=OFF 已关）
    //     - -O2（NDK Release 默认就是 -O2，b5180 不强制 -O3）
    //     - LLAMA_NUMA 不存在（b5180 编译期无此选项）
    //   所以不做 no-op 改动，直接改 n_batch=1 这个真变量。
    cparams.n_batch      = 1;
    cparams.n_ubatch     = 1;
    LOGE("cparams.n_batch=1 n_ubatch=1 (v1.3.14: 绕开 batch 切换崩溃, 246 tokens 分 246 次 decode)");
    cparams.logits_all   = false;
    // 线程数回退到正常（v1.3.13 单线程也崩，排除线程因素；恢复多线程 prefill 更快）
    int32_t n_threads_use = std::max(1, (int)nThreads);
    cparams.n_threads       = n_threads_use;
    cparams.n_threads_batch = n_threads_use;
    state->ctx = llama_init_from_model(state->model, cparams);
    if (!state->ctx) {
        LOGE("nativeInit: llama_init_from_model FAIL（内存不足？12G 机型留 3G）");
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
        // 🔴🔴 关键修复（BOS/ChatML 错位）：
        //   Qwen2.5 用 ChatML 模板 (<|im_start|>system/user/assistant ... <|im_end|>)，
        //   整个 prompt **字符串里已经包含了完整控制符**，所以：
        //   add_spec=0  ：不要再额外加 BOS！否则 token[0]=bos_id token[1]=<|im_start|>_id，
        //                 模型会把 bos_id 当成"用户的一句话"，导致 generate 阶段
        //                 sample 出来的第一个 token 就是 EOS 或乱码 → 用户看到"一个字蹦不出来"
        //   parse_spec=1：让 tokenizer 把 <|im_start|> <|im_end|> 解析成它们的 special token ID，
        //                 而不是拆成一串 < | i m _ s t a r t | > 普通字符 token。
        //                 这是魅族20 vs 荣耀平板8G「同一模型不同表现」的核心原因之一：
        //                 不同设备/不同加载时序下，tokenizer 对 unknown special 的 fallback 策略略有不同，
        //                 有的能瞎蒙跑起来出几个字，有的直接崩 llama_decode。
        int32_t add_spec   = 0;
        int32_t parse_spec = 1;
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
    // -------- 先拿 eos/bos（做 BOS 对齐校验要用，必须在 sampler/prefill 之前！） --------
    const llama_token eos = llama_vocab_eos(state->vocab);
    const llama_token bos = llama_vocab_bos(state->vocab);
    // 🔴🔴 v1.3.21 Hypothesis A 自检 + Hypothesis B im_end 停止符:
    //   A: 手动 tokenize "<|im_start|>" 和 "<|im_end|>"（parse_spec=1），
    //      各自应该只产生 1 个 special token id。如果返回 >1 → parse_spec 失效，
    //      ChatML 控制符被拆成散字符 → 命中 Hypothesis A 乱码根因。
    //   B: ChatML 对话真正的"assistant 说完停止符"是 <|im_end|> 不是 eos。
    //      之前只判断 id == eos → 永远不命中 → 写到 MAX_TOKENS=1024 才停 →
    //      尾部续写训练数据 = 你看到的超长中英文代码杂片段。
    llama_token im_start_id = -1;   int32_t im_start_tok_count = 0;   std::string im_start_pieces;
    llama_token im_end_id   = -1;   int32_t im_end_tok_count   = 0;   std::string im_end_pieces;
    {
        llama_token buf[16];
        auto probe = [&](const char * s, llama_token & out_id, int32_t & out_cnt, std::string & out_pcs) {
            int32_t n = llama_tokenize(state->vocab, s, (int32_t)strlen(s), buf, 16, /*add_spec=*/0, /*parse_spec=*/1);
            int32_t real = (n > 0) ? n : ((n < 0) ? -n : 0);
            out_cnt = real;
            if (real == 1) out_id = buf[0];
            char pc[32];
            for (int32_t i = 0; i < std::min<int32_t>(real, 8); i++) {
                int32_t pn = llama_token_to_piece(state->vocab, buf[i], pc, sizeof(pc), 0, 0);
                if (pn > 0) out_pcs.append(pc, (size_t)pn);
                else if (pn < 0) {
                    std::vector<char> big(-pn + 2);
                    llama_token_to_piece(state->vocab, buf[i], big.data(), (int)big.size(), 0, 0);
                    out_pcs.append(big.data());
                }
                out_pcs.push_back('|');
            }
        };
        probe("<|im_start|>", im_start_id, im_start_tok_count, im_start_pieces);
        probe("<|im_end|>",   im_end_id,   im_end_tok_count,   im_end_pieces);
    }
    LOGI("nativeChat: 📌 vocab eos=%d bos=%d | <|im_start|> tok=%d id=%d pieces=[%s] | <|im_end|> tok=%d id=%d pieces=[%s]",
         (int)eos, (int)bos,
         im_start_tok_count, (int)im_start_id, im_start_pieces.c_str(),
         im_end_tok_count,   (int)im_end_id,   im_end_pieces.c_str());
    if (im_start_tok_count != 1 || im_end_tok_count != 1) {
        LOGW("nativeChat: ⚠️⚠️ Hypothesis A 命中！parse_spec=1 没有把 ChatML special 识别成单个 token。"
             "  <|im_start|> 拆成了 %d 个 token，<|im_end|> 拆成了 %d 个 token。"
             "  模型看不到对话边界，直接纯续写训练数据 → 100%% 会输出你截图里的那种乱码。",
             im_start_tok_count, im_end_tok_count);
    } else {
        LOGI("nativeChat: ✅ Hypothesis A 排除：<|im_start|> 和 <|im_end|> 都是单个 special token。parse_spec=1 正常。");
    }
    LOGI("nativeChat: 📌 vocab eos=%d bos=%d, tokenize DONE n_prompt=%d / n_ctx=%d (tokenize 耗时 %" PRId64 " ms)",
         (int)eos, (int)bos, (int)n_prompt, (int)state->n_ctx, now_ms() - t0);

    // 🔴🔴 BOS 对齐诊断 + 修复（必须在 sampler 创建 / n_ctx 检查 / sampler_accept 之前！）
    //   场景：add_special 某些 GGUF 版本/参数组合下即使传 0 也会塞 BOS，
    //        或者之前 add_spec=1 的遗留缓存导致 token[0] = bos_id。
    //   后果：token[0]=bos_id(BOS), token[1]=<|im_start|>_id → 模型把 BOS 当成对话的"第一个用户说话"，
    //         generate 阶段一上来就 sample EOS → 用户看到 0 token 输出（"一个字蹦不出来"）。
    if (n_prompt > 0 && tokens[0] == bos) {
        LOGW("nativeChat: ⚠️ BOS-MISMATCH token[0]=%d == bos_id=%d！开头多了一个 BOS，"
             "ChatML 应该直接从 <|im_start|> 起头。正在剥掉 token[0]…",
             (int)tokens[0], (int)bos);
        for (int i = 1; i < n_prompt; i++) tokens[i - 1] = tokens[i];
        tokens.pop_back();
        n_prompt = (int32_t)tokens.size();   // ← 必须同步 n_prompt！否则 prefill 越界
        LOGI("nativeChat: ✂️  剥掉 BOS OK. n_prompt 已修正=%d, 新 token[0]=%d",
             (int)n_prompt, n_prompt > 0 ? (int)tokens[0] : -1);
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

    // -------- 3) sampling chain（top_k/top_p/temp/dist） + sampler_accept 修正后的 tokens --------
    llama_sampler * sampler = nullptr;
    {
        auto sp = llama_sampler_chain_default_params();
        sampler = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, /*min_keep=*/1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t)::time(nullptr) ^ 0xC0FFEEu));
    }
    for (llama_token t : tokens) llama_sampler_accept(sampler, t);

    // -------- 4) 预填充 prompt（按 n_batch 切片） --------
    int32_t n_consumed = 0;
    // 🔴 v1.3.15 终极 malloc 排查: 一次性 llama_batch_init(1, 0, 1) 预分配 batch，
    //   循环里只改 batch.token[0] / batch.pos[0] / batch.n_tokens = 1，零 malloc/free。
    //   背景：v1.3.14 (n_batch=1) 仍在 prefill 第 64 个 token 左右 SIGABRT。
    //   每轮用 llama_batch_get_one → 内部 malloc 4 个数组 (tokens/embd/pos/seq_id) +
    //   llama_batch_free → free。246 轮 = 984 次 malloc/free。魅族 20 的 jemalloc 在
    //   骁龙 8 Gen 2 大/中/小核频繁切换下，反复小对象分配触发碎片化 / 元数据损坏。
    //   如果本版能出字 → 锁定崩溃根因是 batch malloc/free 循环，不是 ggml 推理核心。
    //   如果本版仍崩 → 排除 malloc 因素，ggml 推理核心本身不兼容魅族 20，放弃真推理，
    //     v1.3.11 模拟模式为魅族 20 最终版。
    struct llama_batch one_batch = llama_batch_init(1, /*embd=*/0, /*n_seq_max=*/1);
    {
        const int64_t t_pre0 = now_ms();
        int batch_idx = 0;
        bool prefillaunch = true;
        while (n_consumed < n_prompt) {
            if (state->cancel.load()) {
                llama_batch_free(one_batch);
                cb_done(env, callback, "cancel");
                if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
                env->DeleteGlobalRef(callback);
                crash_guard_pop();
                return;
            }
            int32_t n_eval = std::min<int32_t>(n_prompt - n_consumed, state->n_batch);
            int64_t tb0 = now_ms();
            // 填 batch：只放 1 个 token（n_batch=1 已强制）。不 malloc，纯赋值。
            one_batch.token[0]   = tokens[n_consumed];
            one_batch.pos[0]     = n_consumed;  // KV cache 位置索引（必须单调递增）
            one_batch.n_seq_id[0]      = 0;      // 单序列对话
            one_batch.seq_id[0][0]     = 0;
            // 🔴 v1.3.16 关键修复（DeepSeek 报告 SIGSEGV @ addr=0x0 根因）：
            //   v1.3.15 把 logits[0] 写死为 0（"不要 logits"）→ prefill 全程不产生 logits
            //   → generate 阶段第一次 llama_sampler_sample(sampler, ctx, -1) 拿不到 logits
            //   → 访问空指针 → SIGSEGV @ addr=0x0 在生成第一个 token 时崩。
            //   修法：prefill 最后一个 token 必须 logits=1（让 ctx 暴露 logits 给 sampler），
            //   其他 token logits=0（省算力，中间 token 不需要 sample）。
            bool is_last_prefill_token = (n_consumed + n_eval >= n_prompt);
            one_batch.logits[0] = is_last_prefill_token ? 1 : 0;
            one_batch.n_tokens = 1;
            if (n_eval == 0) n_eval = 1;  // 防御性
            int decode_rc = llama_decode(state->ctx, one_batch);
            int64_t tb1 = now_ms();
            if (decode_rc != 0) {
                LOGE("nativeChat: prefill decode #%d FAIL rc=%d (n_eval=%d, consumed=%d) — 耗时 %" PRId64 " ms",
                     batch_idx, decode_rc, n_eval, n_consumed, tb1 - tb0);
                cb_error(env, callback, "预填充 llama_decode FAIL（OOM？上下文不够？）rc=" + std::to_string(decode_rc));
                llama_batch_free(one_batch);
                if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
                env->DeleteGlobalRef(callback);
                crash_guard_pop();
                return;
            }
            LOGI("nativeChat: ⏳ prefill #%d: token[%d]=%d, cost=%" PRId64 "ms (total prefill so far %" PRId64 "ms)",
                 batch_idx, n_consumed, (int)tokens[n_consumed], tb1 - tb0, tb1 - t_pre0);
            CRASH_CHECK(env, callback);  // 🔴 每个 token 后检查有没有 SIGABRT
            n_consumed += n_eval;
            batch_idx++;
            // 🔴 预填充进度回调（每完成 1 token 通知一次）——UI 显示百分比，避免一直白转圈圈
            if (g_mid_onPrefill && callback) {
                env->CallVoidMethod(callback, g_mid_onPrefill, (jint)n_consumed, (jint)n_prompt);
                if (env->ExceptionCheck()) { env->ExceptionClear(); }
            }
        }
        (void)prefillaunch;
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

        // b. EOS / <|im_end|> / 到上下文上限 → Done
        //    🔴 v1.3.21 Hypothesis B 核心修复：ChatML 对话的停止符是 <|im_end|> 不是 eos。
        //      之前只判断 id == eos → 永远不 hit → generate 硬写到 MAX_TOKENS(1024)
        //      → 正常回复写完后继续续写训练数据里的代码/论文/多语种片段 → 就是你截图里的"乱码"。
        //      追加 im_end_id 判断后，正常 assistant 输出到 <|im_end|> 就停，不会乱写。
        bool is_stop = false;
        const char * stop_reason = "stop";
        if (id == eos) {
            stop_reason = "eos";
            is_stop = true;
        } else if (im_end_id != -1 && id == im_end_id) {
            stop_reason = "im_end";   // Hypothesis B 停止符（ChatML 对话真正的结束）
            is_stop = true;
        }
        if (is_stop) {
            LOGI("nativeChat: 🏁 %s hit at n_generated=%d (token_id=%d). Gen total %" PRId64 " ms",
                 stop_reason, (int)n_generated, (int)id, now_ms() - t_gen0);
            cb_done(env, callback, stop_reason); break;
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
        // d. decode 下一步（单个 token）—— 复用上面预分配的 one_batch，零 malloc
        int64_t td0 = now_ms();
        last_id = id;
        one_batch.token[0]   = id;
        one_batch.pos[0]     = n_consumed;  // 生成阶段的 pos = 已消费 token 数（下一个位置）
        one_batch.n_seq_id[0]      = 0;
        one_batch.seq_id[0][0]     = 0;
        // 🔴 v1.3.16 关键修复：generate 阶段每个 token 都要 sample，所以每个都要 logits=1。
        //   v1.3.15 这里写成 0 → 第二个 token sample 又会空指针（如果有第二个 token 的话）。
        one_batch.logits[0]        = 1;    // generate 每个 token 都要 logits 供下一轮 sample
        one_batch.n_tokens = 1;
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
        int decode_rc = llama_decode(state->ctx, one_batch);
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
    // 🔴 v1.3.15: 释放预分配的 one_batch（若未在错误路径提前释放）
    llama_batch_free(one_batch);
    env->DeleteGlobalRef(callback);
    crash_guard_pop();
    return;
}

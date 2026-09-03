// =====================================================
// qwen_jni.cpp — 极简 Qwen 推理器的 JNI 入口
// 单独编为 libqwen-jni.so, 与 libxuedi-llama.so 互不干扰.
// Kotlin 侧类: com.xuedi.coder.model.QwenInferEngine (与 llama 的 LlamaJniEngine 分路, 同包)
//
// v1.3.25-fix6: 新增 SIGSEGV/SIGABRT/SIGBUS/SIGFPE 信号捕获 → 写 crash_log + LOGE,
//   避免 "生成阶段闪退 诊断包抓不到原因" (同 llama_jni.cpp 机制).
// =====================================================
#include "qwen_infer.h"
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// ====== signal handler ======
#include <signal.h>
#include <ucontext.h>
#include <dlfcn.h>
#include <cstdio>
#include <cinttypes>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>

#define LOG_TAG  "qwen-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG, __VA_ARGS__)

static char       g_crash_dir[512] = "";   // Kotlin 传 crash_log 目录 (通常 App externalFilesDir)
static std::mutex g_crash_mutex;

// —— 全局状态：必须放在信号处理函数之前声明，否则 C++ 报 undeclared identifier ——
static JavaVM    * g_vm    = nullptr;
static std::mutex  g_mutex;
static QwenModel * g_model = nullptr;  // 全局单例, 由 load / release 管理
// 🔴 v1.3.25-fix18: 第二次消息不显示根因: 首 token 超时后旧 nativeGenerate 仍在跑
//   (C++ 初版没实现 cancel), 第二次发送又启动 1 个并发 qwen_generate → 资源争抢/死锁
//   → 加 g_gen_running 互斥标记 + g_cancel 全局取消 flag.
static std::atomic<bool> g_gen_running {false};
static std::atomic<bool> g_cancel      {false};
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_QwenInferEngine_nativeCancel(JNIEnv*, jobject) {
    g_cancel.store(true);
}
// 让 qwen_infer.cpp / qwen_forward.cpp 能检查取消.
extern "C" bool qwen_should_cancel() { return g_cancel.load(); }

static void write_crash_log(const char * line) {
    if (!line) return;
    std::lock_guard<std::mutex> l(g_crash_mutex);
    // 优先写 crash_dir, 失败再回退到 /data/local/tmp
    FILE * fp = nullptr;
    char path[1024];
    if (g_crash_dir[0]) {
        snprintf(path, sizeof(path), "%s/qwen_crash_log.txt", g_crash_dir);
        fp = fopen(path, "a");
    }
    if (!fp) {
        snprintf(path, sizeof(path), "/data/local/tmp/qwen_crash_log.txt");
        fp = fopen(path, "a");
    }
    if (fp) {
        fputs(line, fp); fputc('\n', fp); fflush(fp); fclose(fp);
    }
    LOGE("%s", line);  // 同步打到 logcat (会出现在诊断包里)
}

static struct sigaction g_old_segv, g_old_abrt, g_old_bus, g_old_fpe;
static void gqwen_signal_handler(int sig, siginfo_t * info, void * ctx) {
    (void)ctx;
    char line[1024];
    const char * sig_name = "UNKNOWN";
    const char * guess = "native crash";
    switch (sig) {
        case SIGSEGV: sig_name = "SIGSEGV"; guess = "内存访问越界 (nullptr/野指针/munmap后访问/KV cache写溢出)"; break;
        case SIGABRT: sig_name = "SIGABRT"; guess = "abort()/assert失败 → 可能 OOM mmap 失败 / ARM NEON 指令非法 / C++ std::terminate"; break;
        case SIGBUS:  sig_name = "SIGBUS";  guess = "mmap 文件被截断 / 总线错误 (tensor mmap 越界)"; break;
        case SIGFPE:  sig_name = "SIGFPE";  guess = "浮点除零 (sampler/div0)"; break;
    }
    void * offending_pc = nullptr;
#ifdef __arm64__
    if (ctx) {
        ucontext_t * uc = (ucontext_t *)ctx;
        offending_pc = (void*)uc->uc_mcontext.pc;
    }
#endif
    Dl_info dli;
    const char * lib_name = "?";
    const char * sym_name = "?";
    if (offending_pc && dladdr(offending_pc, &dli) && dli.dli_fname) {
        lib_name = dli.dli_fname;
        sym_name = dli.dli_sname ? dli.dli_sname : "(no-symbol)";
    }
    snprintf(line, sizeof(line),
        "[qwen-signal] %s code=%d addr=%p pc=%p lib=%s sym=%s → %s",
        sig_name, info ? info->si_code : -1,
        info ? info->si_addr : nullptr,
        offending_pc, lib_name, sym_name, guess);
    write_crash_log(line);

    // 再写一条上下文快照
    if (g_model) {
        auto & c = g_model->cfg;
        snprintf(line, sizeof(line),
            "[qwen-signal] model loaded: n_layer=%d n_embd=%d n_head=%d n_head_kv=%d head_dim=%d vocab=%d max_seq=%d",
            c.n_layer, c.n_embd, c.n_head, c.n_head_kv, c.head_dim, c.vocab_size, c.max_seq_len);
        write_crash_log(line);
    } else {
        write_crash_log("[qwen-signal] model NOT loaded (crash during load?)");
    }
    // 卸载信号处理 → 重新触发默认行为 (让系统正常杀死/ tombstone, 但我们的日志先落盘了)
    struct sigaction sa{}; sa.sa_handler = SIG_DFL; sigemptyset(&sa.sa_mask);
    if (sig == SIGSEGV) sigaction(SIGSEGV, &sa, &g_old_segv);
    if (sig == SIGABRT) sigaction(SIGABRT, &sa, &g_old_abrt);
    if (sig == SIGBUS)  sigaction(SIGBUS,  &sa, &g_old_bus);
    if (sig == SIGFPE)  sigaction(SIGFPE,  &sa, &g_old_fpe);
    raise(sig);
}
static void gqwen_install_signals() {
    static bool installed = false;
    if (installed) return;
    installed = true;
    struct sigaction sa{};
    sa.sa_sigaction = gqwen_signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaddset(&sa.sa_mask, SIGSEGV);
    sigaddset(&sa.sa_mask, SIGABRT);
    sigaddset(&sa.sa_mask, SIGBUS);
    sigaddset(&sa.sa_mask, SIGFPE);
    sigaction(SIGSEGV, &sa, &g_old_segv);
    sigaction(SIGABRT, &sa, &g_old_abrt);
    sigaction(SIGBUS,  &sa, &g_old_bus);
    sigaction(SIGFPE,  &sa, &g_old_fpe);
    LOGI("signal handlers installed (SIGSEGV/ABRT/BUS/FPE)");
}

// =====================================================
//  回调桥: 把 C 回调转为 Java 接口调用
// =====================================================
struct JniBridge {
    jobject  callback = nullptr; // 全局引用: com.xuedi.coder.llm.QwenGenerateCallback
    jmethodID onToken  = nullptr;
    jmethodID onDone   = nullptr;
    jmethodID onLog    = nullptr;

    ~JniBridge() {
        if (callback && g_vm) {
            JNIEnv * env;
            bool detach = false;
            int r = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (r == JNI_EDETACHED) {
                if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) detach = true;
            }
            if (env) env->DeleteGlobalRef(callback);
            if (detach) g_vm->DetachCurrentThread();
        }
    }
};

static void cb_token_wrap(void * ud, int32_t id, const char * piece, int32_t piece_len) {
    auto * br = (JniBridge *)ud; if (!br || !br->callback || !br->onToken) return;
    JNIEnv * env; bool detach = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    jbyteArray arr = env->NewByteArray(piece_len);
    if (piece_len > 0 && piece) env->SetByteArrayRegion(arr, 0, piece_len, (const jbyte*)piece);
    env->CallVoidMethod(br->callback, br->onToken, (jint)id, arr);
    if (arr) env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) g_vm->DetachCurrentThread();
}
static void cb_done_wrap(void * ud, const char * reason) {
    auto * br = (JniBridge *)ud; if (!br || !br->callback || !br->onDone) return;
    JNIEnv * env; bool detach = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    jstring s = env->NewStringUTF(reason ? reason : "");
    env->CallVoidMethod(br->callback, br->onDone, s);
    if (s) env->DeleteLocalRef(s);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) g_vm->DetachCurrentThread();
}
static void cb_log_wrap(void * ud, const char * msg) {
    __android_log_print(ANDROID_LOG_INFO, "qwen-core", "%s", msg ? msg : "");
    auto * br = (JniBridge *)ud; if (!br || !br->callback || !br->onLog) return;
    JNIEnv * env; bool detach = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        detach = true;
    }
    jstring s = env->NewStringUTF(msg ? msg : "");
    env->CallVoidMethod(br->callback, br->onLog, s);
    if (s) env->DeleteLocalRef(s);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) g_vm->DetachCurrentThread();
}

// =====================================================
//  JNI 方法: com.xuedi.coder.model.QwenInferEngine
// =====================================================
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    g_vm = vm;
    gqwen_install_signals();  // v1.3.25-fix6: 早装信号, 后续任何 SIGxxx 都先抓再崩
    LOGI("JNI_OnLoad: libqwen-jni.so loaded");
    return JNI_VERSION_1_6;
}

// Kotlin 在系统加载后设置 crash 日志写目录
// Kotlin 侧调用: nativeSetCrashLogDir(App.instance.getExternalFilesDir(null)!!.absolutePath)
extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_QwenInferEngine_nativeSetCrashLogDir(
        JNIEnv * env, jobject, jstring jdir) {
    if (!jdir) return;
    const char * d = env->GetStringUTFChars(jdir, nullptr);
    if (d) {
        std::lock_guard<std::mutex> l(g_crash_mutex);
        strncpy(g_crash_dir, d, sizeof(g_crash_dir)-1);
        g_crash_dir[sizeof(g_crash_dir)-1] = 0;
        // 创建目录 (可能不存在)
        mkdir(g_crash_dir, 0777);
        LOGI("crash log dir: %s", g_crash_dir);
        env->ReleaseStringUTFChars(jdir, d);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xuedi_coder_model_QwenInferEngine_nativeLoadModel(
        JNIEnv * env, jobject, jstring jpath) {
    if (!jpath) return JNI_FALSE;
    const char * path = env->GetStringUTFChars(jpath, nullptr);
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_model) { qwen_free_model(g_model); g_model = nullptr; }
    char * err = qwen_load_model(path, g_model);
    env->ReleaseStringUTFChars(jpath, path);
    if (err || !g_model) {
        LOGE("nativeLoadModel failed: %s", err ? err : "null model");
        ::free(err);
        return JNI_FALSE;
    }
    LOGI("nativeLoadModel OK. %s", qwen_dump_model(g_model).c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xuedi_coder_model_QwenInferEngine_nativeIsLoaded(JNIEnv*, jobject) {
    return g_model ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_QwenInferEngine_nativeRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_model) { qwen_free_model(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_xuedi_coder_model_QwenInferEngine_nativeGenerate(
        JNIEnv * env, jobject,
        jstring jprompt, jint jmax, jfloat jtemp, jfloat jtopp, jint jtopk, jlong jseed,
        jobject jcb)
{
    // 🔴 v1.3.25-fix18: 重入保护。旧的 nativeGenerate 仍在跑（超时 cancel 没真的停 native）→
    //   第二次会并发死锁。先清 cancel，再 CAS 拿 g_gen_running 锁。
    g_cancel.store(false);
    bool expected = false;
    if (!g_gen_running.compare_exchange_strong(expected, true)) {
        // 上一次还在跑 → 先发 cancel 等它自己检测到跳出（最多几 ms 到 1 token 时间）
        //   但如果等很久也不行。这里直接把上一次视作僵尸，返回"正在重置，请再发一次"。
        //   直接 return：让 Kotlin 端 cb_done "busy"。
        LOGE("nativeGenerate: 上一次推理仍在运行（没实现真 cancel）→ 回 busy");
        // 为了让 Java 拿到反馈，调 onDone("busy")
        jclass cls = env->GetObjectClass(jcb);
        jmethodID mDone = env->GetMethodID(cls, "onDone", "(Ljava/lang/String;)V");
        if (mDone) {
            jstring s = env->NewStringUTF("busy");
            env->CallVoidMethod(jcb, mDone, s);
            env->DeleteLocalRef(s);
        }
        env->DeleteLocalRef(cls);
        return;
    }

    if (!g_model) {
        g_gen_running.store(false);
        LOGE("nativeGenerate: model not loaded");
        return;
    }
    const char * cprompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = cprompt ? cprompt : "";
    env->ReleaseStringUTFChars(jprompt, cprompt);

    // 缓存 jmethodIDs
    auto br = std::make_unique<JniBridge>();
    jclass cls = env->GetObjectClass(jcb);
    br->onToken = env->GetMethodID(cls, "onToken", "(I[B)V");
    br->onDone  = env->GetMethodID(cls, "onDone",  "(Ljava/lang/String;)V");
    br->onLog   = env->GetMethodID(cls, "onLog",   "(Ljava/lang/String;)V");
    br->callback = env->NewGlobalRef(jcb);
    env->DeleteLocalRef(cls);
    if (!br->onToken || !br->onDone || !br->callback) {
        g_gen_running.store(false);
        LOGE("nativeGenerate: callback method lookup failed");
        return;
    }
    QwenCallbacks cb{};
    cb.token = cb_token_wrap;
    cb.done  = cb_done_wrap;
    cb.log   = cb_log_wrap;
    cb.ud    = br.get();

    char * err = qwen_generate(g_model, prompt, (int32_t)jmax, (float)jtemp,
                               (float)jtopp, (int32_t)jtopk, (uint32_t)jseed, cb);
    if (err) {
        cb_log_wrap(br.get(), err);
        cb_done_wrap(br.get(), "error");
        ::free(err);
    }
    g_gen_running.store(false);
    // br 自动析构
}

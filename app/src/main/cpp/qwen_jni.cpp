// =====================================================
// qwen_jni.cpp — 极简 Qwen 推理器的 JNI 入口
// 单独编为 libqwen-jni.so, 与 libxuedi-llama.so 互不干扰.
// Kotlin 侧类: com.xuedi.coder.model.QwenInferEngine (与 llama 的 LlamaJniEngine 分路, 同包)
// =====================================================
#include "qwen_infer.h"
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG  "qwen-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG, __VA_ARGS__)

static JavaVM * g_vm = nullptr;
static std::mutex g_mutex;
static QwenModel * g_model = nullptr;  // 全局单例, 由 load / release 管理

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
    LOGI("JNI_OnLoad: libqwen-jni.so loaded");
    return JNI_VERSION_1_6;
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
    if (!g_model) {
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
        LOGE("nativeGenerate: callback method lookup failed");
        return;
    }
    QwenCallbacks cb{};
    cb.token = cb_token_wrap;
    cb.done  = cb_done_wrap;
    cb.log   = cb_log_wrap;
    cb.ud    = br.get();

    // 后台线程跑, 释放 JNI 线程以免阻塞 UI.
    // 这里为了简单用 detach 生成线程 (QwenCallbacks 已经会 AttachCurrentThread).
    // 但 2-3h 初版, 直接同步跑也行, 用户本来就是异步启动. 我们同步跑, 简单.
    char * err = qwen_generate(g_model, prompt, (int32_t)jmax, (float)jtemp,
                               (float)jtopp, (int32_t)jtopk, (uint32_t)jseed, cb);
    if (err) {
        cb_log_wrap(br.get(), err);
        cb_done_wrap(br.get(), "error");
        ::free(err);
    }
    // br 自动析构
}

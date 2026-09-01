/*
 * M5-2 占位 JNI so：验证 Gradle+NDK+CMake+链接llama静态库整个链路能过。
 * 只做 1 件事：JNI_OnLoad 返回 JNI_VERSION_1_6。
 *
 * M5-4 会把这个文件替换为真正的 llama_jni.cpp：
 *   · native_loadModel(modelPath, n_ctx, n_threads) -> jlong (llama_state*)
 *   · native_release(jlong handle)
 *   · native_tokenize(jlong handle, jstring text) -> jintArray
 *   · native_chat_stream(jlong handle, jstring system, jstring user, jobject flowCallback)
 *     （每 decode 出 1 个 piece，callback.onToken(piece)；全链路 __android_log_print 打日志）
 *
 * 严格遵循 Experience 571549：
 *   · 不调用不存在的 llama_batch_add
 *   · batch 严格按当前版本 llama_batch 字段手动填
 *   · capacity = prompt_tokens.size，至少等于实际 token 数
 *   · pos 连续递增，prompt decode 完后 generation 首 token pos = n_past
 *   · 只最后 1 token logits = true
 */
#include <jni.h>
#include <android/log.h>

#define XUEDI_LOG_TAG  "XuediLlama"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  XUEDI_LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, XUEDI_LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("JNI_OnLoad: libxuedi-llama.so 占位 so 加载成功。"
         "（M5-2 链路验证，M5-4 会替换为真 llama.cpp JNI 推理桥）");
    return JNI_VERSION_1_6;
}

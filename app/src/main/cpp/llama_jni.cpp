/*
 * 【M5-4 真推理】xuedi-coder JNI bridge：llama_jni.cpp
 *
 * 严格对照 llama.cpp b4835 官方 API（拉自 https://raw.githubusercontent.com/ggml-org/llama.cpp/b4835/include/llama.h）
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

#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "LlamaJNI", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "LlamaJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "LlamaJNI", __VA_ARGS__)

// --------------------------------------------------------------------
// 全局：JavaVM + callback method IDs（JNI_OnLoad 缓存，避免每次反射 FindClass）
// --------------------------------------------------------------------
static JavaVM * g_vm = nullptr;

static jclass   g_cls_Callback = nullptr; // global ref
static jmethodID g_mid_onToken = nullptr;
static jmethodID g_mid_onDone  = nullptr;
static jmethodID g_mid_onError = nullptr;

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
    if (!g_mid_onToken || !g_mid_onDone || !g_mid_onError) {
        LOGE("JNI_OnLoad: GetMethodID 有 null（onToken=%p onDone=%p onError=%p）—— Kotlin interface 方法签名改了？",
             (void*)g_mid_onToken, (void*)g_mid_onDone, (void*)g_mid_onError);
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

    LOGI("nativeInit: 开始加载 GGUF → %s (nCtx=%d, nThreads=%d, nGpuLayers=%d)", path, (int)nCtx, (int)nThreads, (int)nGpuLayers);

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

    // 3) create context
    auto cparams = llama_context_default_params();
    cparams.n_ctx        = (uint32_t)nCtx;
    cparams.n_batch      = std::min<uint32_t>((uint32_t)nCtx, 512U);   // CPU 推理批大小保守一点
    cparams.n_ubatch     = std::min<uint32_t>((uint32_t)nCtx, 512U);
    cparams.logits_all   = false;
    // 线程数：直接在 cparams 里设置（llama.h 317-318 行：n_threads 生成单 token / n_threads_batch 批处理）
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
        LOGE("nativeChat: state null / 未初始化");
        JNIEnv * env = ensure_env();
        cb_error(env, jcallback, "JNI 推理状态异常：模型未加载。请到设置页重新导入并设为当前。");
        return;
    }
    state->cancel.store(false);

    JNIEnv * env = ensure_env();
    if (!env) {
        LOGE("nativeChat: ensure_env FAIL —— 无法 attach JVM");
        return;
    }
    jobject callback = env->NewGlobalRef(jcallback);
    if (!callback) { LOGE("nativeChat: NewGlobalRef(callback) FAIL"); return; }

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
    if (user.empty())   { cb_error(env, callback, "用户输入为空"); env->DeleteGlobalRef(callback); return; }

    std::string prompt;
    prompt.reserve(system.size() + user.size() + 160);
    prompt += "<|im_start|>system\n";   prompt += system;   prompt += "<|im_end|>\n";
    prompt += "<|im_start|>user\n";     prompt += user;     prompt += "<|im_end|>\n";
    prompt += "<|im_start|>assistant\n";

    LOGD("nativeChat: prompt 长度=%zu bytes, system=%d user=%d", prompt.size(), (int)system.size(), (int)user.size());

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
        int32_t add_spec   = 1;   // add_special=true：BOS 开头加一个
        int32_t parse_spec = 0;   // parse_special=false：避免 ChatML special 名查 control token 失败
        // 第一步：估算真实 token 数（无论正负，最后 abs）
        int32_t need = llama_tokenize(state->vocab, prompt.c_str(), (int32_t)prompt.size(),
                                      nullptr, 0, add_spec, parse_spec);
        if (need == 0) {
            // 极少场景：空串（理论上前面判过）、或 tokenizer 没初始化
            cb_error(env, callback, "tokenizer 返回 0 个 token（GGUF 缺 vocab 数据？）");
            env->DeleteGlobalRef(callback);
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
                env->DeleteGlobalRef(callback);
                return;
            }
        }
        // real <= cap（如果 real < cap 说明 estimate 估多了，截断就好）
        if (real > (int32_t)tokens.size()) real = (int32_t)tokens.size();
        tokens.resize((size_t)real);
    }
    const int32_t n_prompt = (int32_t)tokens.size();
    LOGI("nativeChat: prompt tokenized n_prompt=%d / n_ctx=%d", (int)n_prompt, (int)state->n_ctx);
    if (n_prompt >= state->n_ctx - 16) {
        cb_error(env, callback, "输入过长（token 数=" + std::to_string(n_prompt) +
                                " 已接近上下文上限 " + std::to_string(state->n_ctx) + "）。请缩短提问或提高 nCtx。");
        env->DeleteGlobalRef(callback);
        return;
    }

    // -------- 3) sampling chain：和 main.cpp 默认一致（top_k/top_p/temp/dist；简化不接 penalties） --------
    llama_sampler * sampler = nullptr;
    {
        auto sp = llama_sampler_chain_default_params();
        sampler = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, /*min_keep=*/1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t)::time(nullptr) ^ 0xC0FFEEu));
    }
    // 接收 prompt 本身到 penalty 上下文（虽然我们没开 penalty chain，但 accept 对 sampler chain 结构本身没副作用）
    for (llama_token t : tokens) llama_sampler_accept(sampler, t);

    // -------- 4) 预填充 prompt（按 n_batch 切片） --------
    const llama_token eos = llama_vocab_eos(state->vocab);
    int32_t n_consumed = 0;
    {
        bool prefillaunch = true;
        while (n_consumed < n_prompt) {
            if (state->cancel.load()) {
                cb_done(env, callback, "cancel");
                if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
                env->DeleteGlobalRef(callback);
                return;
            }
            int32_t n_eval = std::min<int32_t>(n_prompt - n_consumed, state->n_batch);
            auto batch = llama_batch_get_one(&tokens[n_consumed], n_eval);
            int decode_rc = llama_decode(state->ctx, batch);
            llama_batch_free(batch);   // 必须释放（batch 内部分配了 seq_id 等指针数组，survey 注释明确说）
            if (decode_rc != 0) {
                cb_error(env, callback, "预填充 llama_decode FAIL（OOM？上下文不够？）");
                if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
                env->DeleteGlobalRef(callback);
                return;
            }
            n_consumed += n_eval;
        }
        (void)prefillaunch;
    }
    LOGD("nativeChat: prompt eval DONE n_consumed=%d", (int)n_consumed);

    // -------- 5) 生成循环：最多 1024 token --------
    const int32_t MAX_TOKENS = 1024;
    int32_t n_generated = 0;
    std::string piece_buf;
    piece_buf.resize(32);  // token_to_piece 通常 4~16 字节，32 覆盖大多数 emoji/中文字符
    llama_token last_id   = 0;

    while (n_generated < MAX_TOKENS) {
        if (state->cancel.load()) { cb_done(env, callback, "cancel"); break; }

        // a. sample 最后一个 logit（idx = -1）
        llama_token id = llama_sampler_sample(sampler, state->ctx, /*idx=*/-1);
        llama_sampler_accept(sampler, id);

        // b. EOS / 到上下文上限 → Done
        if (id == eos) { cb_done(env, callback, "stop"); break; }
        if (n_consumed >= state->n_ctx - 2) { cb_done(env, callback, "context_limit"); break; }

        // c. token → piece（需要再次调用，得到真实字节数）
        //    llama_token_to_piece 签名（6 参数）：vocab, id, buf, length, lstrip, special
        int32_t n = llama_token_to_piece(state->vocab, id, piece_buf.data(), (int32_t)piece_buf.size(),
                                          /*lstrip=*/0, /*special=*/false);
        if (n < 0) {
            // buffer 不够 → 扩大重来（n 是"所需字节数"的负数形式，官方惯例）
            piece_buf.resize((size_t)(-n));
            llama_token_to_piece(state->vocab, id, piece_buf.data(), (int32_t)piece_buf.size(),
                                 /*lstrip=*/0, /*special=*/false);
            n = -n;
        }
        if (n > 0) {
            cb_token(env, callback, std::string(piece_buf.data(), (size_t)n));
        }

        // d. decode 下一步（单个 token）
        last_id = id;
        auto batch = llama_batch_get_one(&id, 1);
        int decode_rc = llama_decode(state->ctx, batch);
        llama_batch_free(batch);
        if (decode_rc != 0) {
            cb_error(env, callback, "生成阶段 llama_decode FAIL (token " + std::to_string(n_generated) + ")");
            break;
        }
        n_consumed++;
        n_generated++;
    }

    // 正常 MAX_TOKENS 达到（没 EOS 且没 cancel） → 也算 Done
    if (n_generated >= MAX_TOKENS && !state->cancel.load() && last_id != eos) {
        cb_done(env, callback, "length");
    }
    LOGI("nativeChat: DONE. n_generated=%d, last_id=%d, eos=%d",
         (int)n_generated, (int)last_id, (int)eos);

    if (sampler) { llama_sampler_free(sampler); sampler = nullptr; }
    env->DeleteGlobalRef(callback);
    return;
}

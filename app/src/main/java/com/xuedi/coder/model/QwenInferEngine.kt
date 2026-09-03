package com.xuedi.coder.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 【v1.3.24 极简 Qwen 推理器】从零写的轻量推理引擎，不依赖 llama.cpp 的解码循环。
 *
 * 设计目标（用户指令）：
 *  · 仅支持 Qwen2.5-1.5B Q4_K_M GGUF
 *  · 使用 ggml 库矩阵乘法（不手写 NEON）
 *  · 每次只处理 1 个 token，不做复杂 batch
 *  · JNI 接口简单：load_model(path) + generate(prompt, callback)
 *
 * 配套 C++ 实现：
 *  · qwen_jni.cpp     → JNI 桥（本类 native 方法）
 *  · qwen_infer.cpp   → ggml 前向图：RMSNorm / RoPE / GQA Attention / SwiGLU FFN / KV cache / 采样
 *  · ggml_loader.cpp  → GGUF 解析 + 权重 mmap + BPE tokenizer（Qwen 151646 词表）
 *  · 编为独立 libqwen-jni.so，与 libxuedi-llama.so 互不干扰
 *
 * 相对 LlamaJniEngine 的差别：
 *  · 没有 batch、没有 prefill 分块（prompt 全部先 embedding 再逐层串行前向，再逐 token 生成）
 *  · 不调用 llama_tokenize / llama_decode（这两个在 b4835 + 骁龙8Gen2 上触发过 SIGABRT）
 *  · ChatML 拼接在 Kotlin 侧做，C++ 侧只接收纯 prompt 字符串
 */
class QwenInferEngine : LlmEngine {

    companion object {
        private const val TAG = "QwenInferEngine"

        /** libqwen-jni.so 是否已 System.loadLibrary 成功 */
        @Volatile private var libLoaded: Boolean? = null
        @Volatile private var libLoadError: String? = null

        /** 引擎选择开关：Settings 页切换。true=使用新 Qwen 推理器；false=使用旧 LlamaJniEngine。 */
        @Volatile @JvmField var useQwenEngine: Boolean = false

        /** Qwen2.5 系列 special token id 硬编码（词表固定，不依赖探测）。
         *  来源：https://arxiv.org/html/2409.12186v1 — Qwen2.5 系列词表 151646，特殊 token id 固定。 */
        const val EOS_ID: Int = 151643      // <|endoftext|>
        const val BOS_ID: Int = 151644      // <|begin_of_text|>
        const val IM_END_ID: Int = 151645   // <|im_end|>   — ChatML 对话结束
        const val IM_START_STR: String = "<|im_start|>"
        const val IM_END_STR: String = "<|im_end|>"

        /** 采样默认超参（初版不暴露给 UI，Settings 后面可以加） */
        const val DEFAULT_MAX_TOKENS: Int = 512
        const val DEFAULT_TEMP: Float = 0.7f
        const val DEFAULT_TOP_P: Float = 0.8f
        const val DEFAULT_TOP_K: Int = 20
        const val DEFAULT_SEED: Long = 0L   // 0 = C++ 侧用 time() 随机

        fun ensureLibLoaded(): Boolean = synchronized(this) {
            libLoaded?.let { return it }
            val result = runCatching {
                System.loadLibrary("qwen-jni")
                Log.i(TAG, "System.loadLibrary(\"qwen-jni\") ✅ 成功（极简 Qwen 推理器 .so 已加载）")
                true
            }
            libLoadError = result.exceptionOrNull()?.let { t ->
                "${t.javaClass.simpleName}: ${t.message}"
            }
            val ok = result.getOrDefault(false)
            libLoaded = ok
            ok
        }

        fun libStatus(): Pair<Boolean?, String?> = libLoaded to libLoadError
    }

    /** 上一次 loadModel 失败的原因 */
    @Volatile private var lastLoadError: String? = null
    fun lastLoadError(): String? = lastLoadError

    /** 内存中是否已加载模型（给 Settings 状态条用）*/
    fun isModelLoaded(): Boolean = runCatching { nativeIsLoaded() }.getOrDefault(false)

    init { ensureLibLoaded() }

    // =================================================================
    // JNI ↔ Kotlin 回调接口：签名必须严格匹配 qwen_jni.cpp 里的 GetMethodID
    //   onToken  → "(I[B)V"   (token_id, UTF-8_bytes)
    //   onDone   → "(Ljava/lang/String;)V" (stop_reason)
    //   onLog    → "(Ljava/lang/String;)V" (diagnostic message)
    // =================================================================
    private interface QwenGenerateCallback {
        fun onToken(id: Int, piece: ByteArray)
        fun onDone(reason: String)
        fun onLog(msg: String)
    }

    // =================================================================
    // 公开 API：加载模型
    // =================================================================

    fun loadModel(ggufAbsolutePath: String): Boolean {
        lastLoadError = null
        val libOk = ensureLibLoaded()
        if (!libOk) {
            lastLoadError = "JNI lib 加载失败（${libStatus().second ?: "未知"}）。请确认安装包完整、架构 arm64-v8a。"
            Log.e(TAG, "loadModel ❌ $lastLoadError")
            return false
        }
        // === v1.3.25-fix6: 把 crash 日志目录告诉 C++，一有信号就落盘 + 打 logcat ===
        runCatching {
            val ctx: android.content.Context? = try {
                val appClass = Class.forName("com.xuedi.coder.App")
                val field = appClass.getDeclaredField("instance")
                field.isAccessible = true
                field.get(null) as? android.content.Context
            } catch (_: Throwable) { null }
            val dir = ctx?.getExternalFilesDir(null)?.absolutePath
                ?: ctx?.filesDir?.absolutePath
                ?: ggufAbsolutePath.substringBeforeLast("/models/").substringBeforeLast('/')
            nativeSetCrashLogDir(dir)
            Log.i(TAG, "crashLogDir → $dir")
        }.onFailure { t -> Log.w(TAG, "setCrashLogDir failed (非致命): ${t.message}") }

        if (ggufAbsolutePath.isBlank()) {
            lastLoadError = "模型文件路径为空"
            return false
        }
        val f = java.io.File(ggufAbsolutePath)
        if (!f.exists() || !f.isFile) {
            lastLoadError = "模型文件不存在：$ggufAbsolutePath"
            Log.e(TAG, "loadModel ❌ $lastLoadError")
            return false
        }
        if (f.length() < 1024 * 1024) {
            lastLoadError = "模型文件过小（${f.length()} bytes），非有效 GGUF"
            return false
        }
        // 极简版：不保留旧 ctx —— 直接 release → load（单例模型，C++ 侧 g_model 管理）
        runCatching { nativeRelease() }
        val ok = runCatching { nativeLoadModel(ggufAbsolutePath) }.getOrDefault(false)
        if (!ok) {
            if (lastLoadError == null) {
                // v1.3.25-fix6: 提示用户去诊断页看 qwen-loader 日志，不再是千篇一律"损坏/内存不足"
                lastLoadError = buildString {
                    append("nativeLoadModel 返回 false。\n")
                    append("根因请立刻去：设置页 → 推理诊断 → 看「设置页黑底日志框」里以【qwen-loader】开头的逐-KV 日志。\n")
                    append("—— v1.3.25-fix6 新增定位：每一条 KV 都会打印 key=名字 + vtype=类型 + off=位置。\n\n")
                    append("典型原因（对应 qwen-loader 最后一条 FAIL 行）：\n")
                    append("1) kv[??] key='???' parse failed unsupported value_type=N — 新版 GGUF 增加了类型，请把 N 的值和上一条成功 KV 的 key 发我，我加支持。\n")
                    append("2) kv[??] key='tokenizer.ggml.merges' 卡在循环 — 可能 merges 数组元素类型非 STRING(8)，请发日志。\n")
                    append("3) n_kv/n_tensors 过大 → header 偏移错，版本号不对？\n")
                    append("4) 正常到最后 kv 都 PASS，但后面 tensor 解析阶段挂 → 请发完整 qwen-loader / qwen-jni 行。\n")
                }
            }
            Log.e(TAG, "loadModel ❌：$lastLoadError")
            return false
        }
        Log.i(TAG, "loadModel ✅ 模型已加载：${f.name} size=${f.length()/1024/1024}MB")
        lastLoadError = null
        return true
    }

    // =================================================================
    // LlmEngine 接口实现：真流式 Flow
    // =================================================================

    override fun chatFlow(system: String, user: String): Flow<ChatChunk> {
        val libOk = ensureLibLoaded()
        if (!libOk) {
            val (_, libErr) = libStatus()
            val msg = buildString {
                append("❌ Qwen 推理器 libqwen-jni.so 加载失败\n")
                append("原因：").append(libErr ?: "未知").append("\n\n")
                append("建议：\n")
                append("1. 确认 APK 版本 ≥ v1.3.24，包含 libqwen-jni.so（arm64-v8a）\n")
                append("2. 可在 Settings 里切回 Llama 引擎模式继续使用")
            }
            Log.e(TAG, "chatFlow ❌ lib 未加载 → 返回 Error。$libErr")
            return flowOf(ChatChunk.Error(RuntimeException(libErr ?: "lib load failed"), msg))
        }
        if (!isModelLoaded()) {
            val diag = lastLoadError ?: "模型尚未加载（isModelLoaded=false）"
            val msg = buildString {
                append("❌ Qwen 推理器：模型未加载\n")
                append("诊断信息：").append(diag).append("\n\n")
                append("解决办法：\n")
                append("1. 设置 → 模型卡 → 点「🔄 加载到内存」\n")
                append("2. 确认模型是 Qwen2.5-1.5B-Instruct Q4_K_M（其他规格初版不支持）\n")
                append("3. 关闭后台 App 释放内存后重试")
            }
            Log.e(TAG, "chatFlow ❌ 模型未加载 → 返回 Error。diag=$diag")
            return flowOf(ChatChunk.Error(RuntimeException(diag), msg))
        }

        // —— 拼 ChatML prompt（Qwen2.5 标准格式，完全在 Kotlin 侧做，C++ 不感知 ChatML）——
        val prompt = buildString {
            append(IM_START_STR).append("system\n").append(system.trim()).append(IM_END_STR).append('\n')
            append(IM_START_STR).append("user\n").append(user.trim()).append(IM_END_STR).append('\n')
            append(IM_START_STR).append("assistant\n")
        }
        Log.i(TAG, "chatFlow → ChatML prompt 长度=${prompt.length} chars，开始 nativeGenerate")

        return callbackFlow {
            val fullSb = StringBuilder()
            val firstTokenReceived = AtomicBoolean(false)
            val cancelled = AtomicBoolean(false)

            // 🔴 同样：nativeGenerate 阻塞 JNI while 循环，必须跑在 Dispatchers.Default
            val job = launch(Dispatchers.Default) {
                val cb = object : QwenGenerateCallback {
                    override fun onToken(id: Int, piece: ByteArray) {
                        // 🔴 安全过滤：如果命中 EOS / IM_END 但 C++ 还没 onDone，先吞掉该 token
                        //    （初版 C++ 停止条件可能有轻微 race，这里兜底）
                        if (id == EOS_ID || id == IM_END_ID) return
                        if (cancelled.get()) return
                        val text = runCatching { String(piece, Charsets.UTF_8) }.getOrDefault("")
                        if (text.isEmpty()) return
                        firstTokenReceived.set(true)
                        fullSb.append(text)
                        trySend(ChatChunk.Token(text = text))
                    }
                    override fun onDone(reason: String) {
                        if (cancelled.get()) { channel.close(); return }
                        trySend(ChatChunk.Done(full = fullSb.toString(), stopReason = reason))
                        channel.close()
                    }
                    override fun onLog(msg: String) {
                        // 初版：诊断日志只打 logcat，不发 ChatChunk 到 UI（避免干扰气泡）
                        Log.i(TAG, "⚙ qwen-core: $msg")
                    }
                }
                val ok = runCatching {
                    nativeGenerate(
                        prompt,
                        DEFAULT_MAX_TOKENS,
                        DEFAULT_TEMP,
                        DEFAULT_TOP_P,
                        DEFAULT_TOP_K,
                        DEFAULT_SEED,
                        cb
                    )
                }
                if (ok.isFailure) {
                    val t = ok.exceptionOrNull()
                    Log.e(TAG, "nativeGenerate 异常：${t?.javaClass?.simpleName} - ${t?.message}")
                    trySend(ChatChunk.Error(
                        RuntimeException(t?.message ?: "nativeGenerate crashed"),
                        "❌ Qwen 推理器 nativeGenerate 异常：${t?.javaClass?.simpleName} - ${t?.message}"
                    ))
                    channel.close()
                }
            }
            // 🔴 v1.3.25-fix16: 首 token 超时从 60s 提到 300s（5分钟）
            //   自写推理器用 naive O(N³) matmul，983 prompt tokens × 28 层 × 多个 1536×8960 矩阵
            //   = 大约 ~120亿次乘加，手机 CPU 上就是需要几分钟。
            //   60s 根本不够，先给 5 分钟让它能跑完。后面加 NEON 优化再缩短。
            val timeoutJob = launch(Dispatchers.Default) {
                delay(300_000L)
                if (!firstTokenReceived.get() && !cancelled.get()) {
                    Log.w(TAG, "chatFlow 首 token 超时(300s) → 发 Error + 关流")
                    cancelled.set(true)
                    trySend(ChatChunk.Error(
                        RuntimeException("首 token 超时(300s)"),
                        "首 token 超时(5分钟)：自写推理器用 naive matmul 太慢。\n" +
                            "建议：① 关掉 Qwen 用 Llama 引擎（快很多）② 用更短的提问 ③ 等后续 NEON 优化"
                    ))
                    channel.close()
                }
            }
            job.invokeOnCompletion { cause ->
                timeoutJob.cancel()
                cause?.let { Log.w(TAG, "chatFlow cancel：${it.message}") }
            }
            awaitClose {
                timeoutJob.cancel()
                // 🔴 初版 C++ 侧暂未实现取消 flag；coroutine cancel 能关流不再收 token，
                //    但 native 线程会继续跑到 EOS/max。后面版本加 atomic cancel 后再调。
                cancelled.set(true)
            }
        }
    }

    override fun release() {
        runCatching {
            if (nativeIsLoaded()) nativeRelease()
            Log.i(TAG, "release() 完成")
        }.onFailure { Log.w(TAG, "release 异常：${it.message}") }
    }

    override fun cancel() {
        // 🔴 v1.3.25-fix18: 真取消！调 nativeCancel 设置 g_cancel=true，
        //   C++ prefill/generate 循环每个 token 检查后立刻跳出。
        Log.i(TAG, "cancel() → nativeCancel (g_cancel=true)")
        runCatching { nativeCancel() }
    }

    // =================================================================
    // JNI native 方法（对应 qwen_jni.cpp 中 Java_com_xuedi_coder_model_QwenInferEngine_*）
    // =================================================================

    /** 返回 true=加载成功；false=失败（原因写入 lastLoadError / logcat） */
    private external fun nativeLoadModel(modelPath: String): Boolean
    /** 当前 g_model 是否已加载 */
    private external fun nativeIsLoaded(): Boolean
    /** 释放 g_model（权重+KV cache 全部 free） */
    private external fun nativeRelease()
    /** v1.3.25-fix6：设置 C++ 信号捕获写 crash_log 的目录（传 externalFilesDir.absolutePath）；
     *  生成阶段若崩 → 在此目录下写 qwen_crash_log.txt + logcat 打 E/qwen-jni */
    private external fun nativeSetCrashLogDir(dir: String)
    /**
     * 阻塞式推理：prompt → tokenize → embedding → 逐层前向 → 采样 → 回调。
     *   onToken(id, bytesUTF8) = 出 1 个 token 就调用
     *   onDone(reason)         = 停止（"eos"/"im_end"/"max_tokens"/"error"）
     *   onLog(msg)             = 诊断日志
     */
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Long,
        callback: QwenGenerateCallback
    )
    /** 🔴 v1.3.25-fix18: 设置 g_cancel=true，让 C++ prefill/generate 循环跳出 */
    private external fun nativeCancel()
}

package com.xuedi.coder.model

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * 【M5-4 真机推理版】LlamaJniEngine —— 封装 JNI bridge 调用 + Kotlin Flow 回调。
 *
 * 相对于 M5-3 骨架，这次做了 4 个关键改变：
 *  1) 新增内部接口 [TokenCallback]：C++ 层解码出 1 个 token 就反射调用 Java onToken(piece)，
 *     我们在 chatFlow 里用 callbackFlow 把它转成 Flow<ChatChunk>（真流式）。
 *  2) 移除了 nativeChat() 的阻塞式调用：因为 C++ 层会在 while(decode) 循环里**反复**回调 Java，
 *     不再是"调用一次返回字符串"的模型。
 *  3) 新增 nativeChatCancel() 支持用户中途取消（C++ 层每次 decode 前判断 g_cancel 原子标志）。
 *  4) 当 JNI 层 native* 方法不存在时（例如 .so 损坏），仍 fallback MockLlmEngine，保证不崩。
 *
 * 配套 C++ 实现：app/src/main/cpp/llama_jni.cpp。
 *
 * 已对照 llama.cpp b4835 API 验证（见 survey 输出）：
 *   - load  ：llama_backend_init → llama_model_load_from_file → llama_init_from_model
 *   - decode：llama_tokenize → 预填充 llama_batch_get_one → llama_decode →
 *             llama_sampler_sample / llama_sampler_accept / llama_token_to_piece
 *   - free  ：llama_free → llama_model_free → llama_backend_free
 */
class LlamaJniEngine : LlmEngine {

    companion object {
        private const val TAG = "LlamaJniEngine"

        /** libxuedi-llama.so 是否已经 System.loadLibrary 成功（单进程只需要 load 一次） */
        @Volatile private var libLoaded: Boolean? = null

        /** 尝试加载 .so；返回 true=已加载可用；false=加载失败（后续调用自动 fallback Mock）。 */
        fun ensureLibLoaded(): Boolean = synchronized(this) {
            libLoaded?.let { return it }
            val ok = runCatching {
                System.loadLibrary("xuedi-llama")
                Log.i(TAG, "System.loadLibrary(\"xuedi-llama\") ✅ 成功（JNI_OnLoad 里已 llama_backend_init）")
                true
            }.getOrElse { t ->
                Log.e(TAG, "System.loadLibrary(\"xuedi-llama\") ❌ 失败：${t.javaClass.simpleName} - ${t.message}")
                false
            }
            libLoaded = ok
            ok
        }
    }

    // ---- ctx handle（由 nativeInit 返回，0 表示未初始化） ----
    @Volatile private var ctx: Long = 0L

    // ---- fallback 引擎（JNI 失败后懒创建）----
    private val fallbackLock = Any()
    @Volatile private var fallback: MockLlmEngine? = null
    private fun mkFallbackIfNeed(): MockLlmEngine = fallback
        ?: synchronized(fallbackLock) {
            fallback ?: run {
                Log.w(TAG, "🧱 进入 fallback 模式（native 未实现/失败），回答由 MockLlmEngine 出具占位内容")
                MockLlmEngine().also { fallback = it }
            }
        }

    init { ensureLibLoaded() }

    // =================================================================
    // Java ↔ C++ 回调接口（C++ 层用反射调 Java）
    // =================================================================
    /** C++ 解码循环每出一段 UTF-8 字节（可能是 1~多个 token 合并，提高效率）就调一次。 */
    private interface TokenCallback {
        /** @param piece 已经用 llama_token_to_piece 解码好的字符串片段（UTF-8，可能含 emoji/中文） */
        fun onToken(piece: String)
        fun onDone(reason: String)
        fun onError(message: String)
    }

    // =================================================================
    // 公开 API：供 SettingsPage / 开发者手动调用预热、设模型
    // =================================================================

    /** 加载 GGUF 模型；返回 false = JNI 未实现 / 文件不存在 / 其他失败。 */
    fun loadModel(ggufAbsolutePath: String, nCtx: Int = 4096, nThreads: Int = 4, nGpuLayers: Int = 0): Boolean {
        val libOk = ensureLibLoaded()
        if (!libOk) return false
        if (ggufAbsolutePath.isBlank()) return false
        val newCtx = runCatching {
            nativeInit(ggufAbsolutePath, nCtx, nThreads, nGpuLayers)
        }.getOrElse { t ->
            Log.e(TAG, "nativeInit 异常（JNI 未实现？文件路径？）：${t.javaClass.simpleName} - ${t.message}")
            0L
        }
        if (newCtx == 0L) {
            Log.w(TAG, "loadModel: 加载失败 ctx=0 → fallback Mock 模式。")
            return false
        }
        if (ctx != 0L) runCatching { nativeRelease(ctx) }
        ctx = newCtx
        Log.i(TAG, "loadModel ✅ GGUF 已加载 ctx=$ctx；线程=$nThreads ctx=$nCtx")
        return true
    }

    // =================================================================
    // LlmEngine 接口实现：真流式 Flow
    // =================================================================

    override fun chatFlow(system: String, user: String): Flow<ChatChunk> {
        val libOk = ensureLibLoaded()
        val curCtx = ctx
        // —— 条件不满足 → 直接 fallback Mock ——
        if (!libOk || curCtx == 0L) {
            return mkFallbackIfNeed().chatFlow(system, user)
        }
        // —— 真推理：callbackFlow 包 C++ 回调 ——
        return callbackFlow {
            // 累积所有 token 拼成 full text：Done(reason) 时需要 final 正文，
            // 因为 C++ 层 onDone 只传 stop reason，不传完整回复（流式已经 onToken 吐过了）
            val fullSb = StringBuilder()
            // 取消时顺便让 C++ 端跳出 decode 循环（用户在聊天页中途按取消/关闭APP场景）
            val job = launch {
                val cb = object : TokenCallback {
                    override fun onToken(piece: String) {
                        fullSb.append(piece)
                        trySend(ChatChunk.Token(piece))
                    }
                    override fun onDone(reason: String) {
                        trySend(ChatChunk.Done(fullSb.toString(), reason))
                        channel.close()
                    }
                    override fun onError(message: String) {
                        trySend(ChatChunk.Error(RuntimeException(message), message))
                        channel.close()
                    }
                }
                val ok = runCatching {
                    nativeChat(curCtx, system, user, cb)
                }
                if (ok.isFailure) {
                    val t = ok.exceptionOrNull()
                    Log.e(TAG, "nativeChat 异常：${t?.javaClass?.simpleName} - ${t?.message}；fallback Mock")
                    // native 抛错（常见：native 方法签名对不上 UnsatisfiedLinkError）
                    // → 立即 fallback Mock，UI 不空白
                    channel.close()
                    mkFallbackIfNeed().chatFlow(system, user).collect { send(it) }
                }
            }
            job.invokeOnCompletion { cause ->
                // 取消（聊天页用户停/切后台）：C++ 端 decode while 循环判断 cancel flag
                runCatching { nativeChatCancel(curCtx) }
                cause?.let { Log.w(TAG, "chatFlow cancel：${it.message}") }
            }
            awaitClose()
        }
    }

    override fun release() {
        if (ctx != 0L) runCatching {
            nativeChatCancel(ctx)  // 先取消正在跑的推理
            Thread.sleep(30)        // 给 C++ while 循环一点时间跳出
            nativeRelease(ctx)
            Log.i(TAG, "nativeRelease ctx=$ctx 完成")
        }.onFailure { Log.w(TAG, "nativeRelease 异常：${it.message}") }
        ctx = 0L
        runCatching { fallback?.release() }
    }

    override fun cancel() {
        // 只取消正在跑的推理，不释放模型权重。
        // JNI 端：nativeChatCancel 置原子 flag → C++ while(decode) 下次判断跳出；
        // callbackFlow 的 invokeOnCompletion 会取消 job 并在 awaitClose 后自动关流。
        if (ctx != 0L) runCatching { nativeChatCancel(ctx) }
        runCatching { fallback?.cancel() }
    }

    // =================================================================
    // JNI native 方法（M5-4 在 llama_jni.cpp 中补实现）
    // 注意：native 函数签名必须和 C++ 的 JavaVM FindClass/GetMethodID 严格一致！
    // =================================================================

    /** 返回 ctx ptr（非 0 成功；0 失败） */
    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long

    /** 销毁 ctx（释放 kv cache / 模型权重内存） */
    private external fun nativeRelease(ctx: Long)

    /**
     * 阻塞式推理，内部 while(llama_decode) 循环：
     *   解码出 1 段（token_to_piece 结果）就反射调 [callback.onToken]；
     *   结束 → onDone(reason)；出错 → onError(msg)。
     *
     * @param callback 不能写成匿名 long 传 handle，直接用 Java Object 传 C++ 层，
     *        C++ 通过 FindClass("com/xuedi/coder/model/LlamaJniEngine$TokenCallback") 拿到 methodID。
     */
    private external fun nativeChat(ctx: Long, system: String, user: String, callback: TokenCallback)

    /** 中途取消：C++ 端设 g_cancel 原子标志，下次 llama_decode 前判断跳出 */
    private external fun nativeChatCancel(ctx: Long)
}

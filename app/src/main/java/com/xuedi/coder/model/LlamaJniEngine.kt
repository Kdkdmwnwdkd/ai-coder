package com.xuedi.coder.model

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 【M5-3 骨架版】LlamaJniEngine —— 把 JNI 调用封装成 LlmEngine 接口。
 *
 * 设计说明：
 *  1. native 方法：4 个 bridge 函数（init/release/chat/cancel）。当前 v0.4b 的 C++ 层
 *     还是 M5-2 stub（libxuedi-llama.so 只有 JNI_OnLoad 占位），这些 native 方法
 *     **并没有 C++ 实现**，调用时会抛 `UnsatisfiedLinkError: No implementation found ...`。
 *     → 所有外部入口（chatFlow / release / loadModel）都用 runCatching 包裹，
 *       一旦异常就 fallback 到 MockLlmEngine，保证"装上去不崩、UI 联调继续"。
 *  2. init 时返回 long 型的 ctx ptr（用 JNI 层 `reinterpret_cast<uintptr_t>(ctx)` 写），
 *     0 表示失败；后续所有 native 调用都把它传回 C++。
 *  3. `_fallback` 在第一次真正尝试 JNI 失败后才创建（避免一开始就初始化 Mock 占内存）。
 *  4. ModelManager 当前选中的 GGUF 文件路径由 ChatViewModel / App 负责传进来：
 *     → `loadModel(ggufAbsolutePath, nCtx, nThreads)`。
 *
 * M5-4 上真推理时，只需要在 llama_jni.cpp 里补 Java_com_xuedi_coder_model_LlamaJniEngine_*
 * 四个函数的实现，这一层 Kotlin 代码零改动。
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
                Log.i(TAG, "System.loadLibrary(\"xuedi-llama\") ✅ 成功")
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
                Log.w(TAG, "🧱 进入骨架 fallback：JNI 还没接上真实推理，回答由 MockLlmEngine 出具占位内容")
                MockLlmEngine().also { fallback = it }
            }
        }

    init {
        // 构造时就尝试 load so，失败不抛（外部拿到这个引擎对象就是"安全的"）
        ensureLibLoaded()
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
            Log.e(TAG, "nativeInit 异常（JNI stub？）：${t.javaClass.simpleName} - ${t.message}")
            0L
        }
        if (newCtx == 0L) {
            Log.w(TAG, "loadModel: 加载失败，ctx=0（通常是 C++ nativeInit stub，未上 M5-4）。 fallback Mock 模式继续。")
            return false
        }
        if (ctx != 0L) runCatching { nativeRelease(ctx) }
        ctx = newCtx
        Log.i(TAG, "loadModel ✅ GGUF 已加载，ctx=$ctx；线程=$nThreads")
        return true
    }

    // =================================================================
    // LlmEngine 接口实现
    // =================================================================

    override fun chatFlow(system: String, user: String): Flow<ChatChunk> = flow {
        val libOk = ensureLibLoaded()
        val curCtx = ctx
        if (libOk && curCtx != 0L) {
            // ================================================================
            // 【真推理分支（M5-4 启用）】
            // 真实实现：
            //   nativeChat(curCtx, system, user) → C++ 层循环 llama_batch_decode
            //   + 通过 Java 回调（JavaVM AttachCurrentThread）每次拿到 token
            //     就 reflect 调一个 onToken(text) 方法；onToken 里再 emit 流。
            // 现在 stub：C++ 层 nativeChat 未实现，必然抛 UnsatisfiedLinkError →
            //   onFailure fallback Mock。
            // ================================================================
            val ok = runCatching {
                // 占位：骨架期没有真回调机制，直接抛让下面 catch
                nativeChat(curCtx, system, user)
            }.isSuccess
            if (ok) {
                // 真成功了，什么都不做 —— 真实实现时会由 onToken 里负责 emit Done/Error
                // 这里保险起见 emit 一下 Done，避免 UI pending 永远 true
                emit(ChatChunk.Done("✅ JNI 推理完成（M5-4）", "stop"))
                return@flow
            }
            // 抛了 → 继续 fallback
            Log.w(TAG, "chatFlow: nativeChat 未实现（JNI stub），转 Mock fallback")
        }

        // ========== 骨架 fallback：走 MockLlmEngine ==========
        mkFallbackIfNeed().chatFlow(system, user).collect { emit(it) }
    }

    override fun release() {
        if (ctx != 0L) runCatching {
            nativeRelease(ctx)
            Log.i(TAG, "nativeRelease ctx=$ctx 完成")
        }.onFailure { Log.w(TAG, "nativeRelease 异常：${it.message}") }
        ctx = 0L
        runCatching { fallback?.release() }
    }

    // =================================================================
    // JNI native 方法（M5-4 在 llama_jni.cpp 中补实现）
    // =================================================================

    /** 返回 ctx ptr（非 0 成功；0 失败） */
    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long

    /** 销毁 ctx（释放 kv cache / 模型权重内存） */
    private external fun nativeRelease(ctx: Long)

    /**
     * 阻塞式推理：解码到 EOS / 达到最大 token 数才返回。
     * 真实现里 C++ 端在解码循环中回调 Java onToken(String)。
     * 当前 stub 未实现 → 抛 UnsatisfiedLinkError。
     */
    private external fun nativeChat(ctx: Long, system: String, user: String)
}

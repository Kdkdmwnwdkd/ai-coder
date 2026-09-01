package com.xuedi.coder.model

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.xuedi.coder.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
        /** lib 加载失败时的错误信息（给 SettingsPage / chatFlow 诊断用） */
        @Volatile private var libLoadError: String? = null

        /** 尝试加载 .so；返回 true=已加载可用；false=加载失败。 */
        fun ensureLibLoaded(): Boolean = synchronized(this) {
            libLoaded?.let { return it }
            val result = runCatching {
                System.loadLibrary("xuedi-llama")
                Log.i(TAG, "System.loadLibrary(\"xuedi-llama\") ✅ 成功（JNI_OnLoad 里已 llama_backend_init）")
                true
            }
            libLoadError = result.exceptionOrNull()?.let { t ->
                "${t.javaClass.simpleName}: ${t.message}"
            }
            val ok = result.getOrDefault(false)
            libLoaded = ok
            ok
        }

        /** lib 加载状态：`libLoaded`=null 未尝试 / true 成功 / false 失败；失败时附带错误。 */
        fun libStatus(): Pair<Boolean?, String?> = libLoaded to libLoadError
    }

    // ---- ctx handle（由 nativeInit 返回，0 表示未初始化） ----
    @Volatile private var ctx: Long = 0L

    /** 上一次 loadModel 失败的原因（给 UI Toast / chatFlow 错误文案用） */
    @Volatile private var lastLoadError: String? = null

    /** 当前 ctx 值（给 SettingsPage Toast 显示用）：0=未加载，非0=已加载 */
    fun currentCtx(): Long = ctx

    /** 上一次 loadModel 的错误信息（成功时返回 null） */
    fun lastLoadError(): String? = lastLoadError

    // ---- fallback 引擎（只在 nativeChat 运行期失败时兜底，ctx==0 不再 fallback！）----
    private val fallbackLock = Any()
    @Volatile private var fallback: MockLlmEngine? = null
    private fun mkFallbackIfNeed(): MockLlmEngine = fallback
        ?: synchronized(fallbackLock) {
            fallback ?: run {
                Log.w(TAG, "🧱 进入 fallback 模式（nativeChat 运行期失败），回答由 MockLlmEngine 出具占位内容")
                MockLlmEngine().also { fallback = it }
            }
        }

    init { ensureLibLoaded() }

    // =================================================================
    // OOM 防护：内存预检（loadModel 和 chatFlow 调用前都查，<4GB 直接报错避免 SIGSEGV）
    // =================================================================
    private fun availableMemMB(): Long {
        val am = App.instance.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun checkMemAndReason(minMB: Long): String? {
        val avail = availableMemMB()
        return if (avail < minMB) {
            "可用内存不足（当前 ${avail}MB < 最低要求 ${minMB}MB）。" +
                "\n请：① 关闭所有后台 App（微信、QQ、浏览器等）② 重启手机释放内存 ③ 再尝试加载模型"
        } else null
    }

    // =================================================================
    // Java ↔ C++ 回调接口（C++ 层用反射调 Java）
    // =================================================================
    /** C++ 解码循环每出一段 UTF-8 字节（可能是 1~多个 token 合并，提高效率）就调一次。 */
    private interface TokenCallback {
        /** @param piece 已经用 llama_token_to_piece 解码好的字符串片段（UTF-8，可能含 emoji/中文） */
        fun onToken(piece: String)
        fun onDone(reason: String)
        fun onError(message: String)
        /** 🔴 预填充进度回调（0.0 ~ 1.0）——UI 显示百分比，避免一直白转圈圈 */
        fun onPrefillProgress(consumed: Int, total: Int)
    }

    // =================================================================
    // 公开 API：供 SettingsPage / 开发者手动调用预热、设模型
    // =================================================================

    /**
     * 加载 GGUF 模型；返回是否成功。
     *
     * 失败时会把原因写入 [lastLoadError]，供 SettingsPage / chatFlow 展示给用户。
     * 常见失败原因：
     *   · libxuedi-llama.so 未加载（安装包损坏 / 架构不匹配 arm64-v8a？）
     *   · nativeInit 返回 ctx=0（GGUF 文件坏 / 内存不足 / native 方法签名对不上）
     *   · 文件路径为空或文件不存在
     */
    fun loadModel(ggufAbsolutePath: String, nCtx: Int = 4096, nThreads: Int = 4, nGpuLayers: Int = 0): Boolean {
        lastLoadError = null
        val libOk = ensureLibLoaded()
        if (!libOk) {
            lastLoadError = "JNI lib 加载失败（${libStatus().second ?: "未知"}）。" +
                "请确认安装包是否完整、是否是 arm64-v8a 架构（魅族20 是 arm64）。"
            Log.e(TAG, "loadModel ❌ $lastLoadError")
            return false
        }
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
        // 🔴 OOM 防护 1：加载前查可用内存（3B Q4_K_M 最低 4GB），
        //    内存不够时直接友好报错，不要让 llama_decode 硬顶 → SIGSEGV 闪退。
        checkMemAndReason(4096)?.let {
            lastLoadError = it
            Log.w(TAG, "loadModel ❌ 内存不足：$it")
            return false
        }
        // 🔴 闪退修复 v2：loadModel 前先 cancel 旧 nativeChat + 把 ctx 清 0，
        //    但**不**马上 nativeRelease——让旧 nativeChat 自然跳出后在 invokeOnCompletion 里释放。
        //    之前 sleep(50) 太短，如果 nativeChat 正卡 llama_decode 里（CPU 密集），50ms 不够它跳出，
        //    nativeRelease 就会释放 LlamaState，而 C++ 还在解引用 → SIGSEGV 闪退。
        val oldCtx = ctx
        if (oldCtx != 0L) {
            runCatching { nativeChatCancel(oldCtx) }
            ctx = 0L  // 先清 ctx，让 invokeOnCompletion 里检测到 ctx != curCtx 后 release 旧 state
            Log.i(TAG, "loadModel: 旧 ctx=$oldCtx 已 cancel + ctx 置 0（等旧 nativeChat 自然结束后 release）")
        }
        val newCtx = runCatching {
            nativeInit(ggufAbsolutePath, nCtx, nThreads, nGpuLayers)
        }.getOrElse { t ->
            val msg = "nativeInit 抛异常：${t.javaClass.simpleName} - ${t.message}"
            Log.e(TAG, "loadModel ❌ $msg")
            lastLoadError = msg
            0L
        }
        if (newCtx == 0L) {
            if (lastLoadError == null) {
                lastLoadError = "nativeInit 返回 ctx=0（GGUF 可能损坏、内存不足、或 native 方法未实现）。" +
                    "\n请尝试：① 删除后重新导入 GGUF ② 关闭其他后台 App 释放内存 ③ 重启手机后重试。"
            }
            Log.e(TAG, "loadModel ❌ ctx=0，原因：$lastLoadError")
            return false
        }
        ctx = newCtx
        Log.i(TAG, "loadModel ✅ GGUF 已加载 ctx=$ctx；线程=$nThreads nCtx=$nCtx 文件=${f.name} size=${f.length()/1024/1024}MB")
        lastLoadError = null
        return true
    }

    // =================================================================
    // LlmEngine 接口实现：真流式 Flow
    // =================================================================

    override fun chatFlow(system: String, user: String): Flow<ChatChunk> {
        val libOk = ensureLibLoaded()
        val curCtx = ctx

        // ═══════════════════════════════════════════════════════════════
        // 🔴 诊断修复：模型未就绪时 **不再默默 fallback Mock**，
        //    直接返回 ChatChunk.Error，让用户看到具体原因。
        //    这样用户就不会再碰到"不管哪个模型都跳出固定回应"的情况了。
        // ═══════════════════════════════════════════════════════════════
        if (!libOk) {
            val (_, libErr) = libStatus()
            val msg = buildString {
                append("❌ JNI 引擎未就绪（libxuedi-llama.so 加载失败）\n")
                append("原因：").append(libErr ?: "未知").append("\n\n")
                append("建议：\n")
                append("1. 确认安装包是否完整（请用新版本 v1.2.3+ 的 APK 重装）\n")
                append("2. 魅族20 是 arm64-v8a，请确认 APK 架构匹配\n")
                append("3. 如果问题依旧，请到 GitHub Issue 提交错误日志")
            }
            Log.e(TAG, "chatFlow ❌ lib 未加载 → 返回 Error。$libErr")
            return flowOf(ChatChunk.Error(RuntimeException(libErr ?: "lib load failed"), msg))
        }
        if (curCtx == 0L) {
            val diag = lastLoadError ?: "模型尚未加载或加载失败，ctx=0"
            val msg = buildString {
                append("❌ 模型未加载成功，无法开始推理（ctx=0）\n")
                append("诊断信息：").append(diag).append("\n\n")
                append("解决办法（请按顺序尝试）：\n")
                append("1. 打开「设置」→ 找到你的 GGUF 模型 → 点「设为当前模型」\n")
                append("2. 如提示内存不足：关闭所有后台 App（微信、QQ、浏览器等），或重启手机再试\n")
                append("3. 如提示 GGUF 损坏：在设置里删除该模型 → 重新下载 GGUF → 重新导入\n")
            }
            Log.e(TAG, "chatFlow ❌ ctx=0 → 返回 Error。diag=$diag")
            return flowOf(ChatChunk.Error(RuntimeException(diag), msg))
        }

        // 🔴 OOM 防护 2：聊天前再查一次内存（要 3GB 以上），
        //    避免用户加载完模型后又开了几个后台 App，推理中途 OOM → SIGSEGV
        checkMemAndReason(3072)?.let { reason ->
            Log.w(TAG, "chatFlow ❌ 内存不足：$reason")
            return flowOf(ChatChunk.Error(
                RuntimeException("memory_low"),
                "❌ $reason\n\n另外，建议关闭「场景」页面中不必要的开关，减少 system prompt 占用。"
            ))
        }

        // —— 真推理：callbackFlow 包 C++ 回调 ——
        return callbackFlow {
            // 累积所有 token 拼成 full text：Done(reason) 时需要 final 正文，
            // 因为 C++ 层 onDone 只传 stop reason，不传完整回复（流式已经 onToken 吐过了）
            val fullSb = StringBuilder()
            // 🔴 首 token 45s 超时（从 15s 拉长：手机 CPU 上 3B 模型预填充 500 token 要 20-30s，
            //    15s 会把正常推理也杀掉。45s 留足余量，极端内存紧张场景仍能报错而非一直转圈圈）
            val firstTokenReceived = java.util.concurrent.atomic.AtomicBoolean(false)
            // 取消时顺便让 C++ 端跳出 decode 循环（用户在聊天页中途按取消/关闭APP场景）
            // 🔴 🔴 ANR 致命修复：nativeChat(curCtx,...) 是阻塞式 JNI C++ while 循环（几分钟 CPU 密集），
            //    绝对不能在主线程跑！之前 callbackFlow 继承了 ViewModel 的 Main.immediate，
            //    launch { } 也没切 Dispatcher → 真推理一跑主线程直接卡死 5s → ANR "应用无响应"。
            //    现在强制切到 Dispatchers.Default（专用于 CPU 密集任务的协程池）。
            val job = launch(Dispatchers.Default) {
                val cb = object : TokenCallback {
                    override fun onToken(piece: String) {
                        // 🔴 TODO-4f 并发安全：ctx 被并发 release/loadModel 改变时，旧推理不再回调，
                        //    避免 JNI 拿到已释放的旧 ctx 指针 → SIGSEGV 闪退
                        if (this@LlamaJniEngine.ctx != curCtx) {
                            Log.w(TAG, "onToken 丢弃：ctx 已变（并发 release/loadModel），旧推理不再回调防 SIGSEGV")
                            return
                        }
                        firstTokenReceived.set(true)
                        fullSb.append(piece)
                        trySend(ChatChunk.Token(text=piece))
                    }
                    override fun onDone(reason: String) {
                        if (this@LlamaJniEngine.ctx != curCtx) {
                            Log.w(TAG, "onDone 丢弃：ctx 已变，旧推理不再回调防 SIGSEGV")
                            channel.close()
                            return
                        }
                        trySend(ChatChunk.Done(full=fullSb.toString(), stopReason=reason))
                        channel.close()
                    }
                    override fun onError(message: String) {
                        if (this@LlamaJniEngine.ctx != curCtx) {
                            Log.w(TAG, "onError 丢弃：ctx 已变，旧推理不再回调防 SIGSEGV")
                            channel.close()
                            return
                        }
                        trySend(ChatChunk.Error(RuntimeException(message), message))
                        channel.close()
                    }
                    override fun onPrefillProgress(consumed: Int, total: Int) {
                        if (this@LlamaJniEngine.ctx != curCtx) return
                        // 🔴 不设置 firstTokenReceived——prefill 阶段还没出 token，超时定时器继续跑
                        val percent = if (total > 0) (consumed * 100 / total).coerceIn(0, 100) else 0
                        trySend(ChatChunk.PrefillProgress(percent, consumed, total))
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
            // 🔴 首 token 45s 超时：等 45s 还没出第一个 token（prefill 卡住/内存爆），
            //    就发 ChatChunk.Error + cancel nativeChat + close 流，避免用户以为"一直转卡死"
            val timeoutJob = launch(Dispatchers.Default) {
                delay(45_000L)
                if (!firstTokenReceived.get()) {
                    Log.w(TAG, "chatFlow 首 token 超时(45s)，cancel nativeChat + 发 Error")
                    runCatching { nativeChatCancel(curCtx) }
                    trySend(ChatChunk.Error(
                        RuntimeException("首 token 超时(45s)"),
                        "首 token 超时(45s)：预填充太慢（可能场景插件太多或内存紧张）。" +
                            "建议：① 关掉不必要的场景 ② 重启手机释放内存 ③ 用更短的提问"
                    ))
                    channel.close()
                }
            }
            job.invokeOnCompletion { cause ->
                // 取消（聊天页用户停/切后台）：C++ 端 decode while 循环判断 cancel flag
                timeoutJob.cancel()  // 推理结束/取消时停掉首 token 超时定时器
                runCatching { nativeChatCancel(curCtx) }
                // 🔴 闪退修复 v2：如果 ctx 已经被 loadModel/release 替换或清零，
                //    说明旧 nativeChat 对应的 LlamaState 还没被释放（loadModel 里只 cancel + 清 ctx，
                //    不立即 release）。现在旧 nativeChat 终于结束了，在这里 release 旧 state 防内存泄漏。
                if (this@LlamaJniEngine.ctx != curCtx) {
                    runCatching { nativeRelease(curCtx) }
                    Log.i(TAG, "invokeOnCompletion: 释放已替换的旧 ctx=$curCtx")
                }
                cause?.let { Log.w(TAG, "chatFlow cancel：${it.message}") }
            }
            awaitClose {
                // 流被外层 collect 取消：停超时定时器 + 通知 C++ 跳出 decode 循环
                timeoutJob.cancel()
                runCatching { nativeChatCancel(curCtx) }
            }
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

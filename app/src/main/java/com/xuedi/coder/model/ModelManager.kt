package com.xuedi.coder.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.xuedi.coder.App
import com.xuedi.coder.data.ModelDao
import com.xuedi.coder.data.ModelDatabase
import com.xuedi.coder.data.ModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 本地 GGUF 模型管理：
 * - 所有文件都放在 filesDir/models/<uuid>/<filename>.gguf，避免权限问题。
 * - SAF 选中后用 contentResolver.openInputStream 直接拷贝，不走 MANAGE_EXTERNAL_STORAGE。
 *
 * 【防卡死】：
 *  · `switchAndLoadModel()` 切换模型时先 `LlmEngine.release()` 释放旧权重内存，
 *    再加载新模型；避免"双模型同时驻内存"导致 OOM/卡死。
 *  · 默认 nCtx 从 4096 降到 2048，3B 模型的 KV cache 从 ~1GB 降到 ~512MB，显著降低
 *    lowmemorykiller 概率（聊天/写代码单轮 2048 token 通常足够）。
 *  · `lastLoadedPath` 记录当前已加载的 GGUF 路径，选相同模型时跳过 load，避免重复
 *    nativeInit 分配内存两次 → 卡死。
 */
class ModelManager(private val ctx: Context) {

    private val dao: ModelDao by lazy { ModelDatabase.get(ctx).dao() }
    private val modelsRoot: File by lazy { File(ctx.filesDir, "models").apply { mkdirs() } }

    /**
     * 🔴 v1.3.8：defaultNCtx 2048 → 4096。
     *   Java 层 nCtx 只是 hint，C++ nativeInit 内部会用 probe_max_continuous_mb() 探测
     *   真实连续 mmap 内存并动态计算 dynamic_n_ctx 覆盖 cparams.n_ctx（512~4096）。
     *   这里传 4096 让日志显示与 C++ 封顶一致；实际 n_ctx 仍由 C++ 探针决定。
     */
    var defaultNCtx: Int = 4096

    /** 最近已成功 load 到引擎的 GGUF 绝对路径。null = 没加载或已 release。 */
    private val lastLoadedPath = AtomicReference<String?>(null)

    /** UI 用（SettingsPage 内存状态条显示）：当前真正已加载到 JNI 引擎内存里的 GGUF 绝对路径 */
    fun lastLoadedPath(): String? = lastLoadedPath.get()

    fun observeAll(): Flow<List<ModelEntity>> = dao.observeAll()
    fun observeSelected(): Flow<ModelEntity?> = dao.observeSelected()
    suspend fun getSelected(): ModelEntity? = dao.getSelected()

    // ======================================================================
    // 🆕 v1.3.26-gpu1 方案 C：根据用户偏好路由「默认模型」
    //  —— 当用户还没手动选过模型（dao.getSelected == null）时才生效，
    //     绝不覆盖用户已经明确设置的 selected；如果库里匹配不到合适的模型，
    //     也保持 null，让上游走"请先导入模型"的原有 Toast 流程。
    // ======================================================================
    private fun nameMatches1_5B(name: String): Boolean {
        val n = name.lowercase()
        return ("1.5b" in n || "1_5b" in n || "1-5b" in n) && "qwen" in n
    }
    private fun nameMatches3B(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("3b") && !n.contains("1.5b") && !n.contains("1_5b") && !n.contains("1-5b") && "qwen" in n
    }

    /**
     * 方案 C 入口：如果用户没手动选过模型，按偏好自动挑一个 1.5B / 3B 作为启动默认。
     * 返回 true = 已经自动选中并返回 selected 实体；false = 没选中（没模型/用户手动选过）。
     */
    suspend fun autoSelectInitialByPrefs(): Pair<Boolean, ModelEntity?> {
        val current = dao.getSelected()
        if (current != null) return false to current // 用户已明确选择 → 完全尊重
        val preferFast = (ctx.applicationContext as? App)?.modelPrefs?.getUseFast1_5B()
            ?: ModelPrefsStore.DEFAULT_USE_FAST_1_5B
        val all = dao.getAll()
        if (all.isEmpty()) return false to null
        val target = if (preferFast) {
            all.firstOrNull { nameMatches1_5B(it.fileName) || nameMatches1_5B(it.displayName) }
                ?: all.firstOrNull() // 没 1.5B 就随便挑第一个（有啥用啥）
        } else {
            all.firstOrNull { nameMatches3B(it.fileName) || nameMatches3B(it.displayName) }
                ?: all.firstOrNull() // 没 3B 就随便挑第一个
        }
        target ?: return false to null
        dao.selectOnly(target.id)
        Log.i(TAG, "autoSelectInitialByPrefs: preferFast=$preferFast → 选中 ${target.displayName} id=${target.id}")
        return true to target
    }

    /**
     * 从 SAF 返回的 Uri 导入模型到私有目录 + 写 Room。
     * 成功后返回新 ModelEntity；如果不是 gguf 文件或读取失败抛异常。
     */
    suspend fun importFromUri(uri: Uri, originalNameHint: String? = null): ModelEntity = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val name = originalNameHint
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "unknown.gguf"
        require(name.endsWith(".gguf", ignoreCase = true)) {
            "仅支持 .gguf 模型文件（当前文件：$name）"
        }
        val folder = File(modelsRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(folder, name)
        val size = cr.openInputStream(uri)?.use { ins ->
            target.outputStream().use { outs ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                var written = 0L
                while (ins.read(buf).also { n = it } > 0) {
                    outs.write(buf, 0, n)
                    written += n
                }
                written
            }
        } ?: error("无法读取该文件，请重试。")

        require(size > 1024 * 1024) { "文件过小（${size} B），不是有效的 GGUF 模型。" }

        // GGUF 魔数校验：前4字节 0x47 0x47 0x55 0x46 == "GGUF"
        val head = ByteArray(4)
        target.inputStream().use { it.read(head) }
        val validGGUF = head.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))

        val id = UUID.nameUUIDFromBytes(target.absolutePath.toByteArray()).toString()
        val entity = ModelEntity(
            id = id,
            filePath = target.absolutePath,
            fileName = name,
            sizeBytes = size,
            displayName = name.removeSuffix(".gguf"),
            selected = false,
            validated = validGGUF,
            addedAtMs = System.currentTimeMillis()
        )
        dao.upsert(entity)
        // 如果还没选过模型，自动选中第一个导入的
        if (dao.getSelected() == null) {
            dao.selectOnly(id)
        }
        entity
    }

    suspend fun selectModel(id: String) {
        dao.selectOnly(id)
    }

    /**
     * 【防卡死推荐入口】：切换到指定模型并真正把权重加载到 LlmEngine。
     *
     * 步骤：
     *   1) 如果正在推理 → 先 cancel 再 release（旧权重内存立刻释放）
     *   2) Room 里把 selected 切到新模型
     *   3) 如果目标模型和当前已加载的路径相同 → 跳过加载，省 nativeInit 耗时
     *   4) LlamaJniEngine 调用 `loadModel(path, nCtx=defaultNCtx)`
     *
     * @return Pair(是否成功, 提示文案 —— 含 ctx/错误码诊断信息，Toast 直接给用户看)
     */
    suspend fun switchAndLoadModel(id: String, engine: LlamaEngineHolder): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val m = dao.getById(id)
            ?: return@withContext false to "❌ 模型不存在，请重新导入"
        dao.selectOnly(id)

        val eng = engine.llama() ?: return@withContext false to run {
            val (libOk, libErr) = LlamaJniEngine.libStatus()
            buildString {
                append("❌ JNI 引擎不可用\n")
                append(" · libLoaded=").append(libOk).append("\n")
                if (libErr != null) append(" · 原因：").append(libErr).append("\n")
                append("\n建议：重启 APP 或重新安装 v1.2.3+ APK（确认 arm64-v8a 架构）")
            }
        }

        // 如果新模型路径 = 已加载路径，就不重复 load（防止重复分配内存卡死）
        val prev = lastLoadedPath.get()
        if (prev != null && prev == m.filePath) {
            val ctx = eng.currentCtx()
            return@withContext true to "✅ 已选中：${m.displayName}（ctx=$ctx，已驻留内存）"
        }

        // 释放旧模型权重 + 取消推理
        runCatching {
            eng.cancel()
            Thread.sleep(30)
            eng.release()
        }
        lastLoadedPath.set(null)
        Thread.sleep(100)  // 让系统回收内存页

        // 【诊断】加载前先打日志：文件是否存在、大小、nCtx
        val f = runCatching { java.io.File(m.filePath) }.getOrNull()
        val existSize = f?.let { if (it.exists()) "${it.length()/1024/1024}MB" else "文件不存在" } ?: "?"
        Log.i(TAG, "switchAndLoadModel 开始加载：${m.displayName} 文件状态=$existSize nCtx=$defaultNCtx")

        val ok = runCatching {
            // v1.3.26-code62 (CPU 稳定底包):
            //   · CMake XUEDI_HAS_VULKAN=OFF → 强制 CPU；
            //   · LlamaJniEngine.loadModel 内部 nGpuLayers 已硬编码 0，这里调用无参重载即可。
            (eng as? LlamaJniEngine)?.loadModelRobust(m.filePath)
                ?: eng.loadModelRobust(m.filePath)
        }.getOrDefault(false)

        val ctx = eng.currentCtx()
        val diagErr = eng.lastLoadError()
        val level = (eng as? LlamaJniEngine)?.robustLastLevel
        if (ok) {
            lastLoadedPath.set(m.filePath)
            Log.i(TAG, "switchAndLoadModel ✅ ${m.displayName} 已加载 ctx=$ctx level=$level")
            true to buildString {
                append("✅ 加载成功：").append(m.displayName).append("\n")
                append(" · ctx=0x").append(ctx.toString(16)).append("\n")
                if (level != null) {
                    append(" · 降级档位：").append(level).append("\n")
                    append("   （不是满配说明当前后台内存紧张，如想要满配请关闭所有后台/重启）")
                } else {
                    append(" · 文件=").append(existSize)
                }
            }
        } else {
            Log.e(TAG, "switchAndLoadModel ❌ 加载失败 ctx=$ctx：${m.displayName} diag=$diagErr")
            false to buildString {
                append("❌ 加载失败：").append(m.displayName).append("\n")
                append("（已自动尝试 4 档组合：4096/4 线程 → 2048/2 线程 → 1280/2 线程 → 768/1 线程）\n\n")
                if (diagErr != null) {
                    append("【详细诊断（直接把这整段截图给我）】\n")
                    append(diagErr).append("\n\n")
                }
                append("【3 个兜底方案】\n")
                append("1. 长按多任务 → 把所有后台 App 都杀掉 → 再点一次🔄\n")
                append("2. 重启手机 → 开机后第一个打开本 App → 直接设模型（别先开微信/QQ）\n")
                append("3. 仍不行：到 设置 → 推理诊断 → 开始诊断 → 📋复制完整诊断包 → 发给开发者")
            }
        }
    }

    /** 显式释放当前加载的模型。（v1.3.25-fix22: 仅 Llama 单引擎） */
    fun releaseLoaded(engine: LlamaEngineHolder) {
        runCatching {
            engine.llama()?.also {
                it.cancel()
                Thread.sleep(30)
                it.release()
            }
        }
        lastLoadedPath.set(null)
    }

    suspend fun deleteModel(id: String) {
        val m = dao.getById(id) ?: return
        // 如果删除的正是当前加载的，先释放权重
        if (m.filePath == lastLoadedPath.get()) {
            runCatching {
                (ctx.applicationContext as? com.xuedi.coder.App)?.also { app ->
                    app.llamaEngineRef().also { it.cancel(); Thread.sleep(30); it.release() }
                }
            }
            lastLoadedPath.set(null)
        }
        runCatching { m.filePath.let { File(it).parentFile?.deleteRecursively() } }
        dao.deleteById(id)
    }

    // ---- 静态 ----
    val recommendedModelDisplayName: String get() = "Qwen2.5-Coder-3B-Instruct-Q4_K_M"

    private companion object {
        private const val TAG = "ModelManager"
    }
}

/** 解耦：ModelManager 只需要能拿到 LlamaJniEngine 的引用。这里用 lambda holder 避免直接依赖 App 单例。 */
fun interface LlamaEngineHolder {
    fun llama(): LlamaJniEngine?
}

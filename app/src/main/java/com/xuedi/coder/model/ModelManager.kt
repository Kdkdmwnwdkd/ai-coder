package com.xuedi.coder.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.xuedi.coder.data.ModelDao
import com.xuedi.coder.data.ModelDatabase
import com.xuedi.coder.data.ModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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

    /** 真机低内存：2048 ctx 3B Q4_K_M（KV cache ~512MB）。需要更长上下文可以切到 4096。 */
    var defaultNCtx: Int = 2048

    /** 最近已成功 load 到引擎的 GGUF 绝对路径。null = 没加载或已 release。 */
    private val lastLoadedPath = AtomicReference<String?>(null)

    /** UI 用（SettingsPage 内存状态条显示）：当前真正已加载到 JNI 引擎内存里的 GGUF 绝对路径 */
    fun lastLoadedPath(): String? = lastLoadedPath.get()

    fun observeAll(): Flow<List<ModelEntity>> = dao.observeAll()
    fun observeSelected(): Flow<ModelEntity?> = dao.observeSelected()
    suspend fun getSelected(): ModelEntity? = dao.getSelected()

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
            eng.loadModel(ggufAbsolutePath = m.filePath, nCtx = defaultNCtx)
        }.getOrDefault(false)

        val ctx = eng.currentCtx()
        val diagErr = eng.lastLoadError()
        if (ok) {
            lastLoadedPath.set(m.filePath)
            Log.i(TAG, "switchAndLoadModel ✅ ${m.displayName} 已加载 ctx=$ctx nCtx=$defaultNCtx")
            true to buildString {
                append("✅ 加载成功：").append(m.displayName).append("\n")
                append(" · ctx=0x").append(ctx.toString(16))
                append(" · nCtx=").append(defaultNCtx)
                append(" · ").append(existSize)
            }
        } else {
            Log.e(TAG, "switchAndLoadModel ❌ 加载失败 ctx=$ctx：${m.displayName} diag=$diagErr")
            false to buildString {
                append("❌ 加载失败：").append(m.displayName).append("\n\n")
                append("【诊断】\n")
                append(" · ctx=").append(ctx).append("（0=未初始化）\n")
                append(" · 文件状态：").append(existSize).append("\n")
                append(" · nCtx=").append(defaultNCtx).append("\n")
                if (diagErr != null) append(" · 错误：").append(diagErr).append("\n")
                append("\n【建议（按顺序尝试）】\n")
                append("1. 关闭所有后台 App（微信/QQ/浏览器等）→ 再点一次「设为当前模型」\n")
                append("2. 重启手机 → 打开 APP 直接设置，不要先开其他 App\n")
                append("3. 删除该模型 → 重新下载 GGUF（Qwen2.5-3B-Instruct-Q4_K_M，2.1GB）→ 重新导入\n")
                append("4. 若仍失败，请截图此 Toast + 手机型号 + RAM 大小，到 GitHub Issue 反馈")
            }
        }
    }

    /** 显式释放当前加载的模型（用户想清理内存时调用）。 */
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
                (ctx.applicationContext as? com.xuedi.coder.App)?.llmEngine?.also { eng ->
                    if (eng is LlamaJniEngine) {
                        eng.cancel()
                        Thread.sleep(30)
                        eng.release()
                    }
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

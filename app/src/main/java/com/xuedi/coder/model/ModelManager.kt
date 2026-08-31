package com.xuedi.coder.model

import android.content.Context
import android.net.Uri
import com.xuedi.coder.data.ModelDao
import com.xuedi.coder.data.ModelDatabase
import com.xuedi.coder.data.ModelEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/**
 * 本地 GGUF 模型管理：
 * - 所有文件都放在 filesDir/models/<uuid>/<filename>.gguf，避免权限问题。
 * - SAF 选中后用 contentResolver.openInputStream 直接拷贝，不走 MANAGE_EXTERNAL_STORAGE。
 */
class ModelManager(private val ctx: Context) {

    private val dao: ModelDao by lazy { ModelDatabase.get(ctx).dao() }
    private val modelsRoot: File by lazy { File(ctx.filesDir, "models").apply { mkdirs() } }

    fun observeAll(): Flow<List<ModelEntity>> = dao.observeAll()
    fun observeSelected(): Flow<ModelEntity?> = dao.observeSelected()
    suspend fun getSelected(): ModelEntity? = dao.getSelected()

    /**
     * 从 SAF 返回的 Uri 导入模型到私有目录 + 写 Room。
     * 成功后返回新 ModelEntity；如果不是 gguf 文件或读取失败抛异常。
     */
    suspend fun importFromUri(uri: Uri, originalNameHint: String? = null): ModelEntity {
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
        return entity
    }

    suspend fun selectModel(id: String) {
        dao.selectOnly(id)
    }

    suspend fun deleteModel(id: String) {
        val m = dao.getById(id) ?: return
        runCatching { m.filePath.let { File(it).parentFile?.deleteRecursively() } }
        dao.deleteById(id)
    }

    // ---- 静态 ----
    val recommendedModelDisplayName: String get() = "Qwen2.5-Coder-3B-Instruct-Q4_K_M"
}

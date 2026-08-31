package com.xuedi.coder.theme

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_store")

/**
 * 照片背景 + UI 偏好持久化：
 * - backgroundPath: 保存在 filesDir/backgrounds/ 下的拷贝文件绝对路径；null 表示不启用照片背景。
 * - backgroundAlpha: 前景半透明遮罩透明度（0=无遮罩，1=完全盖掉），
 *   实际背景照片显示强度 = 1 - alpha；所以 alpha 越大照片越淡。
 *   默认 0.18f = 照片很淡、只当点缀，不影响阅读。
 */
class ThemeStore(private val ctx: Context) {

    private val backgroundsDir: File by lazy { File(ctx.filesDir, "backgrounds").apply { mkdirs() } }

    companion object {
        private val KEY_BG_PATH = stringPreferencesKey("bg_path")
        private val KEY_BG_ALPHA = floatPreferencesKey("bg_alpha")
        const val DEFAULT_BG_ALPHA = 0.18f
    }

    val backgroundPathFlow: Flow<String?> =
        ctx.dataStore.data.map { it[KEY_BG_PATH]?.takeIf { p -> File(p).exists() } }
    val backgroundAlphaFlow: Flow<Float> =
        ctx.dataStore.data.map { it[KEY_BG_ALPHA] ?: DEFAULT_BG_ALPHA }

    suspend fun setBackgroundAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0f, 1f)
        ctx.dataStore.edit { it[KEY_BG_ALPHA] = clamped }
    }

    /**
     * 从 SAF Uri 拷贝一份 JPG/PNG/WebP 到 filesDir/backgrounds，
     * 成功后写入 DataStore，返回新文件绝对路径。
     */
    suspend fun importBackgroundFromUri(uri: Uri, originalNameHint: String? = null): String {
        val name = originalNameHint
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "bg_${System.currentTimeMillis()}.jpg"
        val ext = name.substringAfterLast('.', "jpg").lowercase().let {
            if (it in listOf("jpg", "jpeg", "png", "webp")) it else "jpg"
        }
        val target = File(backgroundsDir, "bg_${UUID.randomUUID().toString().take(8)}.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            target.outputStream().use { outs -> ins.copyTo(outs) }
        } ?: error("无法读取该图片，请重新选择。")
        require(target.length() > 0) { "图片导入失败（空文件）" }

        // 清掉旧的背景文件（只保留当前这个，省空间）
        val oldPath = runCatching { backgroundPathFlow.first() }.getOrNull()
        if (oldPath != null && oldPath != target.absolutePath) {
            runCatching { File(oldPath).delete() }
        }
        ctx.dataStore.edit { it[KEY_BG_PATH] = target.absolutePath }
        return target.absolutePath
    }

    suspend fun clearBackground() {
        val old = runCatching { backgroundPathFlow.first() }.getOrNull()
        if (old != null) runCatching { File(old).delete() }
        ctx.dataStore.edit { it.remove(KEY_BG_PATH) }
    }
    private fun InputStream.copyTo(out: java.io.OutputStream) {
        val buf = ByteArray(32 * 1024)
        var n: Int
        while (read(buf).also { n = it } > 0) out.write(buf, 0, n)
    }
}

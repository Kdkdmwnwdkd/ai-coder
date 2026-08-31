package com.xuedi.coder

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.xuedi.coder.ui.screen.AppNavHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 导入模型到 filesDir/models/...；SAF 选择 */
    private val modelImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // 拿到内容读权限（持久化：重启后还能读）
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent_FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = queryDisplayName(contentResolver, uri) ?: "unknown.gguf"
        val app = (applicationContext as App)
        lifecycleScope.launch {
            val toast = Toast.makeText(this@MainActivity, "开始导入模型…", Toast.LENGTH_LONG).also { it.show() }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    app.modelManager.importFromUri(uri, name)
                }
            }
            runCatching { toast.cancel() }
            result
                .onSuccess { m ->
                    val s = "已导入：${m.displayName} (${m.sizeHuman})"
                    Toast.makeText(this@MainActivity, s, Toast.LENGTH_LONG).show()
                }
                .onFailure { t ->
                    val s = "导入失败：${t.message ?: t.javaClass.simpleName}"
                    Toast.makeText(this@MainActivity, s, Toast.LENGTH_LONG).show()
                }
        }
    }

    /** 导入照片背景到 filesDir/backgrounds/... */
    private val backgroundImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent_FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = queryDisplayName(contentResolver, uri)
        val app = (applicationContext as App)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    app.themeStore.importBackgroundFromUri(uri, name)
                }
            }
            result
                .onSuccess {
                    Toast.makeText(this@MainActivity, "背景照片已设置", Toast.LENGTH_SHORT).show()
                }
                .onFailure { t ->
                    Toast.makeText(
                        this@MainActivity,
                        "导入失败：${t.message ?: t.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val app = (applicationContext as App)
        setContent {
            AppNavHost(
                app = app,
                requestImportModel = {
                    runCatching {
                        modelImportLauncher.launch(arrayOf("*/*"))
                    }.onFailure { t ->
                        Toast.makeText(this, "启动文件选择失败：${t.message}", Toast.LENGTH_LONG).show()
                    }
                },
                requestImportBackground = {
                    runCatching {
                        backgroundImportLauncher.launch(arrayOf("image/*"))
                    }.onFailure { t ->
                        Toast.makeText(this, "启动图片选择失败：${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    companion object {
        /** Intent.FLAG_GRANT_READ_URI_PERMISSION 常量值，避免引入 android.content.Intent 符号 */
        private const val Intent_FLAG_GRANT_READ_URI_PERMISSION = 1

        private fun queryDisplayName(cr: ContentResolver, uri: Uri): String? {
            return runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
    }
}

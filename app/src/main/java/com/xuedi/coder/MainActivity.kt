package com.xuedi.coder

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.xuedi.coder.ui.screen.AppNavHost

/**
 * 【新 M3 = UI 层】：setContent 渲染 AppNavHost（4 个 Tab + 底部导航 + 聊天/插件/设置/关于 + 照片背景盒子）
 *
 * 目前 SAF 导入（GGUF/照片）只做了 UI 入口：
 *   · 照片：SettingsPage 里已经有 SAF OpenDocument 启动 + takePersistableUriPermission + 写到 UiBackground，
 *           UI 层立即能看到效果。
 *   · GGUF：SettingsPage 里只有按钮，只弹 Toast 占位（真正把 URI 复制到私有目录 + 写 Room +
 *           启动推理，是 M4=管理层的职责）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = App.instance
        val appScope = app.appScope

        setContent {
            AppNavHost(
                appScope = appScope,
                requestImportModel = {
                    // M4 才真正处理：SAF URI → 私有目录复制 + ModelEntity Room 持久化。
                    Toast.makeText(
                        this,
                        "【占位】选择 GGUF 模型文件…（真正的导入和推理会在新 M4 管理层接入）",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                requestImportBackground = {
                    // M3 层 SettingsPage 已经拿到 URI 并写进 UiBackground 了，
                    // 这里只是给未来接 ThemeStore DataStore 持久化留一个钩子。
                }
            )
        }
    }
}

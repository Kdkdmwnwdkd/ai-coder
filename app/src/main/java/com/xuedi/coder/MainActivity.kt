package com.xuedi.coder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xuedi.coder.ui.screen.AppNavHost

/**
 * 【UI 层】：setContent 渲染 AppNavHost（4 个 Tab + 底部导航 + 聊天/插件/设置/关于 + 照片背景盒子）。
 *
 * SAF 导入（GGUF/照片）均在 SettingsPage 内闭环：
 *   · 照片：SAF OpenDocument + takePersistableUriPermission + ThemeStore 持久化，UI 立即生效。
 *   · GGUF：SAF OpenDocument + ModelManager.importFromUri（复制到私有目录 + Room + 魔数校验）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // code299: 全面屏适配（魅族 20 等长屏机）——内容延伸到状态栏/导航栏后面，
        //   Scaffold 的 inner padding 会自动避开系统栏，不再留黑边/白边。
        enableEdgeToEdge()

        setContent {
            AppNavHost(appScope = App.instance.appScope)
        }
    }
}

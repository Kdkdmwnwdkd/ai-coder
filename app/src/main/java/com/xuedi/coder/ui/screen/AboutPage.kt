package com.xuedi.coder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuedi.coder.BuildConfig

@Composable
fun AboutPage() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text(
            "关于 · AI 编程助手",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                RowItem("应用名称", "AI 编程助手")
                RowItem("版本", "${BuildConfig.VERSION_NAME} (code=${BuildConfig.VERSION_CODE})")
                RowItem("构建类型", BuildConfig.BUILD_TYPE)
                RowItem("包名", BuildConfig.APPLICATION_ID)
                RowItem("Milestone", "新 M3 = UI 层（聊天/插件/设置/关于 页面 + 底部Tab + 照片背景盒子 + SAF入口）")
            }
        }

        Spacer(Modifier.height(14.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("后续里程碑路线", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val lines = listOf(
                    "新 M4 = 管理层：PluginManager / ModelManager / ThemeStore（Gson/Moshi 代替 serialization gradle 插件）",
                    "M5 = JNI llama.cpp 真推理：集成官方 llama.cpp 子模块 + CMake + arm64-v8a 编译 + MockEngine 切换到真引擎",
                    "M6 = 前台保活 + ACTION 标签执行：Flyme 前台服务防杀；解析 <ACTION copy=\"...\"/>、openApp 等",
                    "M7 = Release 签名打包：复用 shimmer_xuedi_release.jks 正式出 Release APK"
                )
                lines.forEachIndexed { i, s ->
                    Spacer(Modifier.height(2.dp))
                    Text("  ${i + 1}. $s", fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun RowItem(title: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

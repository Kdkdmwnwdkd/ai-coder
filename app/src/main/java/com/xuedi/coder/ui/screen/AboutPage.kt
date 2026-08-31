package com.xuedi.coder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuedi.coder.BuildConfig

@Composable
fun AboutPage() {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            Modifier.padding(6.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource_safe("AI编程助手"),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "包名：${ctx.packageName} ｜ ABI：arm64-v8a",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        SectionCard("项目说明") {
            BulletLine("本地运行 GGUF 大模型做代码生成，全程不上传。")
            BulletLine("支持导入照片做壁纸（透明度可调）。")
            BulletLine("可插拔编程场景：Android / Java / Python / Shell。")
            BulletLine("回复中检测到代码块可一键复制，支持 ACTION 标签操作。")
        }
        SectionCard("开源组件") {
            BulletLine("Jetpack Compose / Material3 / Room / DataStore")
            BulletLine("Kotlinx Serialization / Coroutines / Coil")
            BulletLine("推理后端：llama.cpp（M6 JNI 接入）")
            BulletLine("图标：Material Icons Outlined")
        }
        SectionCard("隐私") {
            BulletLine("不联网、不收集日志、无第三方统计 SDK。")
            BulletLine("模型与背景照片都保存在应用私有目录。")
            BulletLine("卸载应用即可清理全部数据。")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable Column.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Text(
        "•  $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 20.sp
    )
}

// 简单兜底：避免 strings.xml 不同步
@Composable
private fun stringResource_safe(default: String): String = runCatching {
    androidx.compose.ui.res.stringResource(id = com.xuedi.coder.R.string.app_name)
}.getOrElse { default }

package com.xuedi.coder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.IntegrationInstructions
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope

/**
 * 【M3 UI 层】场景插件页（不接 PluginManager，用纯内存 Map remember 模拟开关状态）
 * M4=管理层接回来后会替换成 PluginManager.flowAllPlugins 真实的数据。
 */
data class PluginUiItem(
    val id: String,
    val name: String,
    val desc: String,
    val icon: ImageVector
)

@Composable
fun PluginsPage(appScope: CoroutineScope) {
    val items = remember {
        listOf(
            PluginUiItem("android", "Android 场景", "注入 Android/Compose/Room/KMP 等开发 System Prompt + 正则工具", Icons.Outlined.Android),
            PluginUiItem("java", "Java 场景", "JDK17+/Spring Boot/Maven/Gradle 相关注入", Icons.Outlined.Code),
            PluginUiItem("python", "Python 场景", "数据分析/脚本/爬虫/机器学习相关注入", Icons.Outlined.IntegrationInstructions),
            PluginUiItem("shell", "Shell 场景", "bash/zsh/python -c 命令/脚本生成与 ACTION 执行链路", Icons.Outlined.Terminal)
        )
    }
    // 默认 Android 开，其他关
    val switches = remember { mutableStateMapOf<String, Boolean>().also { m ->
        items.forEachIndexed { i, it -> m[it.id] = (i == 0) }
    } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text(
            "场景 · 插件开关",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "（M3 UI 演示版：开关只在本页本地 remember，不会持久化。M4 接入管理层后 → 存入 Room + ThemeStore。）",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { p ->
                val on: Boolean = switches[p.id] == true
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                    )
                ) {
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    p.icon, null,
                                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        },
                        supportingContent = {
                            Text(p.desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingContent = {
                            Switch(
                                checked = on,
                                onCheckedChange = { v -> switches[p.id] = v }
                            )
                        }
                    )
                }
            }
        }
    }
}

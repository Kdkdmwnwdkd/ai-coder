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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.IntegrationInstructions
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xuedi.coder.App
import com.xuedi.coder.plugin.PluginConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 【新 M4 = 管理层】场景插件页 —— 真正接 App.instance.pluginManager。
 *
 * 数据流：
 *   PluginManager (ensureBuiltinPlugins 写 filesDir plugins 下的 plugin.json 到 Room)
 *     → listFlow: Flow<List<Pair<PluginConfig, Boolean>>>
 *       → collectAsStateWithLifecycle
 *         → 渲染卡片 + Switch
 *           → Switch 点击时 appScope.launch { pluginManager.toggle(id, enable) }
 *             → Room 写入 enabled → listFlow 自动 emit 新列表 → UI 刷新
 */
@Composable
fun PluginsPage(appScope: CoroutineScope) {
    val pluginManager = App.instance.pluginManager
    val list: List<Pair<PluginConfig, Boolean>>? by pluginManager.listFlow
        .collectAsStateWithLifecycle(initialValue = null)
    val refreshing by pluginManager.refreshing.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            // 🔴 同样补上：场景页 4 个卡片（每个展开开关 + Prompt 说明），
            //    小屏手机（魅族20 20:9 FHD+）场景 3+4 就容易出屏幕外，没滚动
            //    直接被底部 TabBar 盖掉，用户看不到第 4 个场景开关。
            .verticalScroll(rememberScrollState())
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 14.dp,
                bottom = 34.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "场景 · 插件开关",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (refreshing) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(
                    Modifier
                        .padding(start = 10.dp)
                        .padding(vertical = 2.dp),
                    strokeWidth = 2.dp
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "打开的场景会把对应的 System Prompt 注入到 AI 回答里。开关写入 Room 数据库，关掉APP重开也保存。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        when {
            list == null -> {
                Row(
                    Modifier.fillMaxWidth().padding(top = 30.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            list!!.isEmpty() -> {
                Text(
                    "暂无插件。App 启动时会自动写入 4 个内置场景（Android / Java / Python / Shell），请稍候刷新或返回聊天页重进。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp)
                )
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list!!, key = { (cfg, _) -> cfg.id }) { (cfg, enabled) ->
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
                                            iconFor(cfg.id), null,
                                            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 10.dp)
                                        )
                                        Text(cfg.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    }
                                },
                                supportingContent = {
                                    Text(cfg.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                trailingContent = {
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = { v ->
                                            appScope.launch {
                                                runCatching { pluginManager.toggle(cfg.id, v) }
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// 根据 id 匹配置换图标（PluginConfig 本身不存 Compose Icon，避免跨层依赖 UI）
private fun iconFor(id: String): ImageVector = when (id) {
    "android_dev" -> Icons.Outlined.Android
    "java_backend" -> Icons.Outlined.Code
    "python_script" -> Icons.Outlined.IntegrationInstructions
    "shell_gradle" -> Icons.Outlined.Terminal
    else -> Icons.Outlined.Extension
}

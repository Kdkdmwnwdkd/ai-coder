package com.xuedi.coder.ui.screen

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xuedi.coder.App
import com.xuedi.coder.data.ModelEntity
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置页：
 * - 模型导入/切换/删除 + 推荐模型名复制
 * - 照片背景导入 + 透明度滑块（很淡的默认 0.18f）+ 清除
 * - 其他占位项（M6 真 JNI 推理配置、前台保活开关）
 */
@Composable
fun SettingsPage(
    requestImportModel: () -> Unit,
    requestImportBackground: () -> Unit
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val scope = rememberCoroutineScope()

    val models by app.modelManager.observeAll().collectAsState(initial = emptyList())
    val selectedModel by app.modelManager.observeSelected().collectAsState(initial = null)
    val bgPath by produceState<String?>(initialValue = null, key1 = app) {
        app.themeStore.backgroundPathFlow.collect { value = it }
    }
    val bgAlpha by produceState(initialValue = com.xuedi.coder.theme.ThemeStore.DEFAULT_BG_ALPHA, key1 = app) {
        app.themeStore.backgroundAlphaFlow.collect { value = it }
    }

    var busyModel by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionTitle("1. 本地模型管理")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { requestImportModel() },
                        enabled = !busyModel
                    ) {
                        Icon(Icons.Outlined.Upload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入 GGUF 模型")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (busyModel) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("处理中…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "仅支持 .gguf。会把文件拷贝到应用私有目录，不需要特殊存储权限。推荐模型：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        app.modelManager.recommendedModelDisplayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        copyText(ctx, app.modelManager.recommendedModelDisplayName, "已复制推荐模型名")
                    }) {
                        Icon(Icons.Outlined.ContentCopy, "复制", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                Text(
                    "已导入模型（${models.size}）：",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                if (models.isEmpty()) {
                    Text(
                        "还没有模型。点上方「导入 GGUF 模型」选择文件即可。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        models.forEach { m ->
                            ModelRow(
                                m = m,
                                isSelected = m.id == selectedModel?.id,
                                onSelect = {
                                    scope.launch {
                                        busyModel = true
                                        runCatching { app.modelManager.selectModel(m.id) }
                                        busyModel = false
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        busyModel = true
                                        runCatching { app.modelManager.deleteModel(m.id) }
                                            .onFailure { t ->
                                                Toast.makeText(ctx, "删除失败：${t.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        busyModel = false
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "注：M6 之前推理使用内置 Mock 流式引擎演示；接入真 llama.cpp 后会加载此处选中的真实 GGUF 模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
        }

        SectionTitle("2. 照片背景")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { requestImportBackground() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Icon(Icons.Outlined.Photo, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选择照片")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { app.themeStore.clearBackground() }
                                .onSuccess { Toast.makeText(ctx, "已清除背景", Toast.LENGTH_SHORT).show() }
                        }
                    }) {
                        Text("清除背景")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val file = bgPath?.takeIf { File(it).exists() }?.let { File(it) }
                    Box(
                        Modifier
                            .width(96.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                RoundedCornerShape(10.dp)
                            )
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f))
                    ) {
                        if (file != null) {
                            val model = ImageRequest.Builder(LocalContext.current)
                                .data(file)
                                .crossfade(false)
                                .build()
                            AsyncImage(
                                model = model, contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                            // 蒙层预览
                            val isDark = MaterialTheme.colorScheme.onBackground == Color.White
                            val mask = if (isDark) Color.Black else Color.White
                            Box(Modifier.fillMaxSize().background(mask.copy(alpha = bgAlpha)))
                        } else {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Outlined.Photo, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                Spacer(Modifier.height(4.dp))
                                Text("未设置", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        val pct = ((1f - bgAlpha) * 100).toInt().coerceIn(0, 100)
                        Text(
                            "照片显示强度",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "越左越淡（推荐 80%-90%），默认 ${((1f - com.xuedi.coder.theme.ThemeStore.DEFAULT_BG_ALPHA) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$pct%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(42.dp)
                            )
                            Slider(
                                value = 1f - bgAlpha,
                                onValueChange = { nv ->
                                    val alpha = 1f - nv
                                    scope.launch { app.themeStore.setBackgroundAlpha(alpha) }
                                },
                                modifier = Modifier.weight(1f),
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }
        }

        SectionTitle("3. 其他")
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "本地推理参数、前台保活开关、Sampler 参数、n_ctx 等",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "→ 留到 M6 接入真 JNI llama.cpp 之后再提供。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun ModelRow(
    m: ModelEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(
                    alpha = 0.4f
                ),
                RoundedCornerShape(10.dp)
            )
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Column(Modifier.weight(1f)) {
            Text(m.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    m.sizeHuman,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!m.validated) {
                    Text(
                        "⚠ 未通过魔数校验",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "GGUF",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                m.fileName,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, "删除模型", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun copyText(ctx: Context, text: String, toast: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ai_coder", text))
    if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }
}

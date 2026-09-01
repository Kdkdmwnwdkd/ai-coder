package com.xuedi.coder.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xuedi.coder.App
import com.xuedi.coder.BuildConfig
import com.xuedi.coder.data.ModelEntity
import com.xuedi.coder.model.LlamaEngineHolder
import com.xuedi.coder.model.LlamaJniEngine
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * 设置页：
 *  · 背景照片 + 透明度 Slider：改全局 UiBackground（实时生效 + DataStore 持久化）
 *  · 本地 GGUF 模型：导入（SAF→filesDir/models+Room+GGUF魔数校验）/ 列表显示 / 设为当前 / 删除
 *  （接入真 JNI llama.cpp 后，会自动加载"当前"模型开始推理。）
 */
@Composable
fun SettingsPage(
    currentBg: String?,
    currentAlpha: Float,
    setBg: (String?) -> Unit,
    setAlpha: (Float) -> Unit,
    requestImportModel: () -> Unit,
    requestImportBackground: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- Room 流：已导入模型 + 当前选中模型 ----
    val allModels by App.instance.modelManager.observeAll()
        .collectAsState(initial = emptyList())
    val selectedModel by App.instance.modelManager.observeSelected()
        .collectAsState(initial = null)

    // SAF: 选照片（image/*）→ UI层直接生效
    val pickBgLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            setBg(uri.toString())
            requestImportBackground()
        }
    }

    // SAF: 选 GGUF → 导入到 filesDir/models + 写 Room
    val pickModelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        scope.launch {
            val cr = runCatching { App.instance.modelManager.importFromUri(uri) }
            if (cr.isSuccess) {
                val e = cr.getOrNull()
                val mb = (e?.sizeBytes ?: 0L) / 1024 / 1024
                val validated = e?.validated == true
                Toast.makeText(
                    ctx,
                    "✅ 导入成功：${e?.displayName}（${mb}MB${if (validated) "，GGUF魔数校验通过" else "，未通过GGUF校验"})",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    ctx,
                    "❌ 导入失败：${cr.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        requestImportModel()
    }

    var alphaLocal: Float by remember(currentAlpha) { mutableFloatStateOf(currentAlpha) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text(
            "设置 · 外观 / 模型 / 背景",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

        // ============== 卡片 1：背景照片 + 透明度 ==============
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("背景照片", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { pickBgLauncher.launch(arrayOf("image/*")) },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.padding(end = 6.dp))
                        Text("选择照片")
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 小预览图（当前背景）
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(64.dp)
                                .fillMaxWidth(0.42f),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        ) {
                            if (!currentBg.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(currentBg)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    alpha = alphaLocal
                                )
                            } else {
                                Column(
                                    Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("（纯白）", fontSize = 12.sp)
                                }
                            }
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("透明度", fontSize = 13.sp)
                            Text(
                                "${(alphaLocal * 100).toInt()}%",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Slider(
                    value = alphaLocal,
                    onValueChange = { alphaLocal = it },
                    onValueChangeFinished = { setAlpha(alphaLocal) },
                    valueRange = 0f..1f
                )

                if (currentBg != null) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { setBg(null) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("移除背景（恢复纯白）", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ============== 卡片 2：本地 GGUF 模型（列表 + 导入按钮） ==============
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            )
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("本地 GGUF 模型", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "导入后文件存放在私有目录（filesDir/models）并写入 Room。" +
                        "GGUF 魔数校验通过后可设为「当前加载模型」，后续接入真 JNI llama.cpp 后自动加载推理。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { pickModelLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Outlined.Source, null, Modifier.padding(end = 6.dp))
                    Text("选择 GGUF 模型文件导入")
                }

                Spacer(Modifier.height(12.dp))

                if (allModels.isEmpty()) {
                    Text(
                        "（还没导入模型。推荐文件：Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf · 约 2GB · 魅族20 流畅可跑）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "共 ${allModels.size} 个模型 ｜ 当前加载：" +
                            (selectedModel?.displayName ?: "（未选择）"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    allModels.forEachIndexed { idx, m ->
                        ModelRow(
                            m = m,
                            isActive = selectedModel?.id == m.id,
                            onSetActive = {
                                scope.launch {
                                    val app = App.instance
                                    val holder = LlamaEngineHolder {
                                        app.llmEngine as? LlamaJniEngine
                                    }
                                    val (ok, tip) = app.modelManager.switchAndLoadModel(m.id, holder)
                                    Toast.makeText(ctx, tip,
                                        if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    App.instance.modelManager.deleteModel(m.id)
                                    Toast.makeText(ctx, "🗑 已删除：${m.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        if (idx < allModels.size - 1) Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "当前构建：${BuildConfig.BUILD_TYPE} · v${BuildConfig.VERSION_NAME}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// -------------------------
// 子组件：单个模型条目行
// -------------------------
@Composable
private fun ModelRow(
    m: ModelEntity,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    val mb = (m.sizeBytes / 1024 / 1024).toInt()
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isActive) 1.5.dp else 0.dp, borderColor)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = true)
                ) {
                    if (isActive) {
                        Icon(
                            Icons.Outlined.Verified, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    } else if (!m.validated) {
                        Icon(
                            Icons.Outlined.WarningAmber, null,
                            tint = Color(0xFFD98600),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = m.displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = buildString {
                                append("${mb}MB")
                                append(" · ")
                                append(if (m.validated) "✅ GGUF校验通过" else "⚠️ GGUF校验未通过")
                                if (isActive) append(" · 🔵 当前加载")
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isActive) {
                    OutlinedButton(
                        onClick = onSetActive,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("设为当前模型", fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(0.dp))
                } else {
                    TextButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("✓ 已选中", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.padding(end = 6.dp))
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.padding(end = 2.dp))
                    Text("删除", fontSize = 11.sp, color = Color(0xFFC24141))
                }
            }
        }
    }
}

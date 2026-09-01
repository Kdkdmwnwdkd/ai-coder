package com.xuedi.coder.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.xuedi.coder.R
import com.xuedi.coder.data.ModelEntity
import com.xuedi.coder.model.LlamaEngineHolder
import com.xuedi.coder.model.LlamaJniEngine
import com.xuedi.coder.theme.ThemeMode
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource

/**
 * 设置页（v1.2.5 正式版 UI）
 *   · 分两组卡片：【外观与主题】 / 【本地模型管理】
 *   · 长说明文字、排障步骤、模型下载指南 → 全部迁移到「关于」页（正式应用的设计）
 *   · 模型行：始终有「加载/重新加载到内存」按钮，Room 里 selected=true 但 ctx=0 也能手动点
 *   · 模型行下方显示「内存加载状态」条：✓已加载到内存(ctx=0x...) / 未加载 / 加载失败(具体原因)
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

    // ---- 当前 JNI 引擎内存状态（给模型行的状态条用）----
    val engineSnapshot = remember(allModels) {
        val eng = App.instance.llmEngine as? LlamaJniEngine
        val libSt = eng?.run { LlamaJniEngine.libStatus() }
        LoadDiagSnapshot(
            libLoadedOk = libSt?.first,
            libLoadError = libSt?.second,
            currentCtx = eng?.currentCtx() ?: 0L,
            lastLoadError = eng?.lastLoadError(),
            lastLoadedPath = App.instance.modelManager.lastLoadedPath()
        )
    }

    // SAF: 选照片（image/*）→ UI层直接生效 + ThemeStore 持久化
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

    // SAF: 选 GGUF → 导入到 filesDir/models + 写 Room + GGUF 魔数校验
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
                    buildString {
                        append("✅ 导入成功：").append(e?.displayName)
                        append("（").append(mb).append(" MB")
                        append(if (validated) "，GGUF 校验通过" else "，⚠️ GGUF 校验未通过")
                        append("）")
                    },
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
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // --------- 分组 1：外观与主题 ----------
        SectionHeader(title = stringResource(R.string.settings_group_appearance))
        AppearanceCard(
            currentBg = currentBg,
            alphaLocal = alphaLocal,
            onPickBg = { pickBgLauncher.launch(arrayOf("image/*")) },
            onClearBg = { setBg(null) },
            onAlphaChanged = { alphaLocal = it },
            onAlphaCommit = { setAlpha(alphaLocal) }
        )

        Spacer(Modifier.height(18.dp))

        // --------- 分组 2：本地模型管理 ----------
        SectionHeader(title = stringResource(R.string.settings_group_models))
        ModelsCard(
            allModels = allModels,
            selected = selectedModel,
            engineSnapshot = engineSnapshot,
            onPickModel = { pickModelLauncher.launch(arrayOf("*/*")) },
            onSetActive = { m ->
                scope.launch {
                    val app = App.instance
                    val holder = LlamaEngineHolder { app.llmEngine as? LlamaJniEngine }
                    val (ok, tip) = app.modelManager.switchAndLoadModel(m.id, holder)
                    Toast.makeText(
                        ctx, tip,
                        if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
            },
            onDelete = { m ->
                scope.launch {
                    App.instance.modelManager.deleteModel(m.id)
                    Toast.makeText(ctx, "已删除：${m.displayName}", Toast.LENGTH_SHORT).show()
                }
            }
        )

        Spacer(Modifier.height(14.dp))
        Text(
            "Build ${BuildConfig.BUILD_TYPE} · v${BuildConfig.VERSION_NAME} · " +
                "support-abi=arm64-v8a",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

// ===================================================================
// 子组件：分组标题
// ===================================================================
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp, top = 2.dp),
        letterSpacing = 0.5.sp
    )
}

// ===================================================================
// 子组件：外观卡片（背景预览 + 透明度滑块 + 选照片/恢复纯白按钮）
// ===================================================================
@Composable
private fun AppearanceCard(
    currentBg: String?,
    alphaLocal: Float,
    onPickBg: () -> Unit,
    onClearBg: () -> Unit,
    onAlphaChanged: (Float) -> Unit,
    onAlphaCommit: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeStore = App.instance.themeStore
    val themeMode by themeStore.themeModeFlow.collectAsState(initial = ThemeMode.FOLLOW_SYSTEM)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            // -------- 主题模式选择（3 按钮：浅色 / 深色 / 跟随系统） --------
            Text(
                "主题模式",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeModeChip(
                    label = "浅色",
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { scope.launch { themeStore.setThemeMode(ThemeMode.LIGHT) } }
                )
                ThemeModeChip(
                    label = "深色",
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { scope.launch { themeStore.setThemeMode(ThemeMode.DARK) } }
                )
                ThemeModeChip(
                    label = "跟随系统",
                    selected = themeMode == ThemeMode.FOLLOW_SYSTEM,
                    onClick = { scope.launch { themeStore.setThemeMode(ThemeMode.FOLLOW_SYSTEM) } }
                )
            }

            Spacer(Modifier.height(14.dp))

            // -------- 背景照片 --------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(R.string.settings_bg_pick),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "自定义聊天界面的背景",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onPickBg,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, Modifier.padding(end = 4.dp))
                    Text("选择", fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))

            // 预览
            OutlinedCard(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(78.dp)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(62.dp)
                            .fillMaxWidth(0.42f),
                        color = MaterialTheme.colorScheme.background
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
                                Text("（纯白）", fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(stringResource(R.string.settings_alpha_title), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${(alphaLocal * 100).toInt()}%",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Slider(
                value = alphaLocal,
                onValueChange = onAlphaChanged,
                onValueChangeFinished = onAlphaCommit,
                valueRange = 0f..1f
            )
            if (currentBg != null) {
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClearBg, shape = RoundedCornerShape(10.dp)) {
                        Text(stringResource(R.string.settings_bg_clear),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ===================================================================
// 子组件：本地模型管理卡片（导入按钮 + 模型列表 + 每个模型的内存状态条）
// ===================================================================
@Composable
private fun ModelsCard(
    allModels: List<ModelEntity>,
    selected: ModelEntity?,
    engineSnapshot: LoadDiagSnapshot,
    onPickModel: () -> Unit,
    onSetActive: (ModelEntity) -> Unit,
    onDelete: (ModelEntity) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        stringResource(R.string.settings_group_models),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.settings_group_hint_import),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onPickModel,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Outlined.Source, null, Modifier.padding(end = 5.dp))
                Text("导入 GGUF 模型", fontSize = 13.sp)
            }

            Spacer(Modifier.height(10.dp))

            if (allModels.isEmpty()) {
                EmptyModelsHint()
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已导入 ${allModels.size} 个",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selected != null) {
                        Text(
                            "默认：${selected.displayName}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                allModels.forEachIndexed { idx, m ->
                    ModelRowFormal(
                        m = m,
                        isActive = selected?.id == m.id,
                        engineSnapshot = engineSnapshot,
                        onSetActive = { onSetActive(m) },
                        onDelete = { onDelete(m) }
                    )
                    if (idx < allModels.size - 1) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyModelsHint() {
    OutlinedCard(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CloudOff, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 10.dp)
            )
            Column {
                Text("尚未导入任何模型",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "前往「关于」→「使用指南·模型下载」获取推荐 GGUF 的下载地址，" +
                        "导入后即可开始本地对话推理。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// ===================================================================
// 子组件：单个模型行（正式版）
//   - 模型信息（名称 + 大小 + 校验状态 + 是否是默认）
//   - 操作按钮：
//       · 如果 isActive=false → 「设为当前并加载到内存」（Primary OutlinedButton）
//       · 如果 isActive=true  → 「🔄 重新加载到内存」（始终可点，解决用户截图里 ctx=0 却不能重加载）
//       · 删除按钮（红色）
//   - 下方小状态条：内存加载状态（已加载/未加载/失败原因）
// ===================================================================
@Composable
private fun ModelRowFormal(
    m: ModelEntity,
    isActive: Boolean,
    engineSnapshot: LoadDiagSnapshot,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    val mb = (m.sizeBytes / 1024 / 1024).toInt()
    // 这个模型当前在内存中的状态（注意：data class destructuring 顺序与定义一致，
    // 直接用属性访问更清晰，避免排错）
    val ctxVal: Long = engineSnapshot.currentCtx
    val loadedToMemory = ctxVal != 0L
    val thisIsLastLoaded = engineSnapshot.lastLoadedPath == m.filePath
    val memStatus = when {
        isActive && loadedToMemory && thisIsLastLoaded -> MemStatus.LoadedOk(ctxVal)
        isActive && !loadedToMemory -> {
            // Room 里已设为当前，但内存里 ctx==0（这就是用户截图里的状态）
            val specificErr = engineSnapshot.lastLoadError
            if (specificErr != null) MemStatus.Failed(specificErr)
            else MemStatus.NotLoaded
        }
        isActive -> MemStatus.NotLoaded
        else -> MemStatus.OtherModel  // 别的模型，不显示具体内存状态
    }

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
        border = BorderStroke(if (isActive) 1.3.dp else 0.dp, borderColor)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // 第一行：状态图标 + 模型名 + 校验标识 + 删除按钮
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
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "${mb} MB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SmallTag(
                                text = if (m.validated) "GGUF OK" else "GGUF 校验失败",
                                ok = m.validated
                            )
                            if (isActive) {
                                SmallTag(text = stringResource(R.string.model_active), ok = true, primary = true)
                            }
                        }
                    }
                }
                OutlinedIconButton(
                    onClick = onDelete,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline, null,
                        tint = Color(0xFFC24141)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 第二行：内存加载状态条（只在 isActive 时详细显示，其他模型给一句概览）
            if (isActive) {
                MemoryStatusBar(status = memStatus)
            } else {
                Text(
                    "未设为当前，因此未加载到内存",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // 第三行：操作按钮（正式版：「设为当前并加载到内存」 / 「🔄 重新加载到内存」）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val btnText = if (!isActive) {
                    stringResource(R.string.model_inactive) + "并加载到内存"
                } else {
                    stringResource(R.string.model_reload)
                }
                if (!isActive) {
                    Button(
                        onClick = onSetActive,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(btnText, fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = onSetActive,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Refresh, null,
                            Modifier.padding(end = 3.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(btnText, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ===================================================================
// 小 UI 辅助：SmallTag / MemoryStatusBar
// ===================================================================
@Composable
private fun SmallTag(text: String, ok: Boolean, primary: Boolean = false) {
    val bg = when {
        primary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        ok -> Color(0xFF1A8E4F).copy(alpha = 0.12f)
        else -> Color(0xFFD98600).copy(alpha = 0.12f)
    }
    val fg = when {
        primary -> MaterialTheme.colorScheme.primary
        ok -> Color(0xFF1A8E4F)
        else -> Color(0xFFD98600)
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private sealed class MemStatus {
    object NotLoaded : MemStatus()
    data class LoadedOk(val ctx: Long) : MemStatus()
    data class Failed(val reason: String) : MemStatus()
    object OtherModel : MemStatus()
}

@Composable
private fun MemoryStatusBar(status: MemStatus) {
    when (status) {
        is MemStatus.LoadedOk -> Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CheckCircle, null,
                tint = Color(0xFF1A8E4F),
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                "✓ 已加载到内存  ·  ctx=0x${status.ctx.toString(16)}",
                fontSize = 11.sp,
                color = Color(0xFF1A8E4F),
                fontWeight = FontWeight.SemiBold
            )
        }
        is MemStatus.NotLoaded -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.ErrorOutline, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                "未加载到内存（请点下方「重新加载到内存」按钮，" +
                    "或关闭其他后台 App 释放内存后重试）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )
        }
        is MemStatus.Failed -> {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline, null,
                        tint = Color(0xFFC24141),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(
                        "加载失败（点击下方「重新加载到内存」可重试）：",
                        fontSize = 11.sp,
                        color = Color(0xFFC24141),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    status.reason.take(220) + if (status.reason.length > 220) "…" else "",
                    fontSize = 10.sp,
                    color = Color(0xFFC24141),
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp)
                )
            }
        }
        MemStatus.OtherModel -> { /* 其他模型不显示细节 */ }
    }
}

// ===================================================================
// 当前 JNI 引擎一次快照（Immutable，便于 Compose 重组）
// ===================================================================
private data class LoadDiagSnapshot(
    val libLoadedOk: Boolean?,
    val libLoadError: String?,
    val currentCtx: Long,
    val lastLoadError: String?,
    val lastLoadedPath: String?
)

// ===================================================================
// 主题模式选择 Chip（3 个：浅色 / 深色 / 跟随系统）
// ===================================================================
@Composable
private fun RowScope.ThemeModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(38.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        border = BorderStroke(
            width = if (selected) 1.2.dp else 0.4.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
        ),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

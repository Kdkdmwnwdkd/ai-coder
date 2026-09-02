package com.xuedi.coder.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xuedi.coder.App
import com.xuedi.coder.BuildConfig
import com.xuedi.coder.R
import com.xuedi.coder.data.ModelEntity
import com.xuedi.coder.model.ChatChunk
import com.xuedi.coder.model.LlamaEngineHolder
import com.xuedi.coder.model.LlamaJniEngine
import com.xuedi.coder.model.QwenInferEngine
import com.xuedi.coder.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    // 🔴 v1.3.8 魅族闪退根治：截断 NavHost 页面切换过渡期间传递的 Infinity maxHeight。
    //   NavHost 内部 AnimatedContent + SizeModifier 在过渡期给子组件传 AtMost Infinity，
    //   而 Modifier.fillMaxSize() 在 Infinity 下无法截断（hasBoundedHeight=false 时透传），
    //   → 根 LazyColumn 收到 Infinity 抛 "Vertically scrollable component was measured
    //   with an infinity maximum height constraints"。用屏幕高度作 max 上限兜底：
    //   正常（有限高度）时 heightIn(max=屏幕高度) 不影响滚动；过渡期 Infinity 被截到屏幕高度。
    val screenMaxH = LocalConfiguration.current.screenHeightDp.takeIf { it > 0 }?.dp ?: 800.dp

    // ---- Room 流：已导入模型 + 当前选中模型 ----
    // 🔴 v1.3.7 魅族修复：统一用 collectAsStateWithLifecycle（与 PluginsPage 保持一致），
    //    避免旧的 collectAsState 在某些厂商 ROM 进入 Settings Tab 时因无生命周期感知
    //    在重组期间 collect 未完成时 null 解引用 / Flow cancellation race 导致崩溃。
    val allModels by App.instance.modelManager.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedModel by App.instance.modelManager.observeSelected()
        .collectAsStateWithLifecycle(initialValue = null)

    // ---- 当前 JNI 引擎内存状态（给模型行的状态条用）----
    // 🔴 v1.3.24：根据引擎选择动态读 LlamaJniEngine 或 QwenInferEngine 的诊断信息
    //   Qwen 引擎无 ctx 概念（单例 g_model）→ currentCtx=-1 表示"Qwen 引擎已加载"
    var useQwenSwitch by remember { mutableStateOf(QwenInferEngine.useQwenEngine) }
    val engineSnapshot = runCatching {
        val app = App.instance
        if (useQwenSwitch) {
            val eng = app.qwenEngineRef()
            val libSt = QwenInferEngine.libStatus()
            LoadDiagSnapshot(
                libLoadedOk = libSt.first,
                libLoadError = libSt.second,
                currentCtx = if (eng.isModelLoaded()) -1L else 0L,  // -1=Qwen已驻留(无ctx) 0=未驻留
                lastLoadError = eng.lastLoadError(),
                lastLoadedPath = app.modelManager.lastLoadedPath()
            )
        } else {
            val eng = (app.llmEngine as? LlamaJniEngine)
                ?: app.llamaEngineRef()
            val libSt = eng.let { LlamaJniEngine.libStatus() }
            LoadDiagSnapshot(
                libLoadedOk = libSt.first,
                libLoadError = libSt.second,
                currentCtx = eng.currentCtx(),
                lastLoadError = eng.lastLoadError(),
                lastLoadedPath = app.modelManager.lastLoadedPath()
            )
        }
    }.getOrDefault(
        LoadDiagSnapshot(
            libLoadedOk = false,
            libLoadError = "UI层读取快照失败",
            currentCtx = 0L,
            lastLoadError = null,
            lastLoadedPath = null
        )
    )

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

    // ---- 诊断运行态 ----
    val diagLines = remember { mutableStateListOf<String>() }
    var diagRunning by remember { mutableStateOf(false) }
    // 🔴 v1.3.11 方案A：模拟模式开关状态（镜像 LlamaJniEngine.forceMockMode，
    //    用 remember/mutableStateOf 让 Compose 重组；切换时同步回静态变量）
    var mockMode by remember { mutableStateOf(LlamaJniEngine.forceMockMode) }
    val diagTsFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA) }
    fun ts() = diagTsFmt.format(Date())
    fun addLog(line: String) { diagLines.add("[${ts()}] $line") }
    // ---- 🆕 v1.3.6 手机端：上一次抓日志完整内容缓存（分享时能导全部） ----
    var lastGrabLogcatFull by remember { mutableStateOf(emptyList<String>()) }

    // ⛔ v1.3.5 彻底删除外层 Column + verticalScroll：之前 SettingsPage 一直有
    //    「超过屏幕高度被静默裁剪」vs「加 verticalScroll 后嵌套 LazyColumn 崩」的矛盾。
    //    根治：直接把根布局改成 LazyColumn（自己会滚），每一组内容就是一个 item，
    //    这样无论组再多（后续加更多诊断/偏好设置）也不会被屏幕截断，
    //    同时绝不会出现 IllegalState 嵌套滚动崩。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .heightIn(max = screenMaxH)
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 10.dp,
                bottom = 34.dp
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ---- 分组 1：外观与主题 ----
        item(key = "appearance-group") {
            SectionHeader(title = stringResource(R.string.settings_group_appearance))
        }
        item(key = "appearance-card") {
            AppearanceCard(
                currentBg = currentBg,
                alphaLocal = alphaLocal,
                onPickBg = { pickBgLauncher.launch(arrayOf("image/*")) },
                onClearBg = { setBg(null) },
                onAlphaChanged = { alphaLocal = it },
                onAlphaCommit = { setAlpha(alphaLocal) }
            )
        }
        item(key = "spacer1") { Spacer(Modifier.height(18.dp)) }

        // ---- 分组 2：本地模型管理 ----
        item(key = "models-group") {
            SectionHeader(title = stringResource(R.string.settings_group_models))
        }
        item(key = "models-card") {
            ModelsCard(
                allModels = allModels,
                selected = selectedModel,
                engineSnapshot = engineSnapshot,
                onPickModel = { pickModelLauncher.launch(arrayOf("*/*")) },
                onSetActive = { m ->
                    scope.launch {
                        val app = App.instance
                        val (ok, tip) = if (useQwenSwitch) {
                            app.modelManager.switchAndLoadQwenModel(m.id, app.qwenEngineRef())
                        } else {
                            val holder = LlamaEngineHolder { app.llamaEngineRef() }
                            app.modelManager.switchAndLoadModel(m.id, holder)
                        }
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
        }
        item(key = "spacer2") { Spacer(Modifier.height(18.dp)) }

        // ---- 分组：引擎选择（v1.3.24 新增，Llama vs Qwen 极简推理器）----
        item(key = "engine-select-group") {
            SectionHeader(title = "⚙ 推理引擎（v1.3.24 beta）")
        }
        item(key = "engine-select-card") {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (useQwenSwitch) "使用 Qwen 极简推理器（beta）" else "使用 Llama 引擎（v1.3.16 稳定版）",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (useQwenSwitch)
                                "ON=从零自写推理器（ggml算子，绕过 llama_tokenize/llama_decode 魅族20闪退路径，仅支持 1.5B Q4_K_M）"
                            else
                                "OFF=原有 llama.cpp b5180 推理链（3B/1.5B 都支持，v1.3.16 亲测稳定版路径）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 14.sp
                        )
                    }
                    Switch(
                        checked = useQwenSwitch,
                        onCheckedChange = { newChecked ->
                            useQwenSwitch = newChecked
                            QwenInferEngine.useQwenEngine = newChecked
                            val tip = if (newChecked)
                                "✅ 已切 Qwen 极简推理器（仅支持 Qwen2.5-1.5B Q4_K_M）"
                            else
                                "已切回 Llama 引擎（稳定版路径）"
                            Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
        item(key = "spacer2b") { Spacer(Modifier.height(18.dp)) }

        // ---- 分组：模拟模式（仅 Llama 引擎模式下显示，对 Qwen 引擎无意义）----
        if (!useQwenSwitch) {
        item(key = "mock-mode-group") {
            SectionHeader(title = "🧱 模拟模式（防闪退兜底）")
        }
        item(key = "mock-mode-card") {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用模拟回复（不跑真模型，防闪退）",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "ON=逐字返回预设回复，绕过 C++ 引擎；OFF=调用真推理（如仍崩，等方案B升级 llama.cpp）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = mockMode,
                        onCheckedChange = { newChecked ->
                            mockMode = newChecked
                            LlamaJniEngine.forceMockMode = newChecked
                            val tip = if (newChecked) "已切换到模拟模式" else "已切换到真实推理模式"
                            Toast.makeText(ctx, tip, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
        } // ← if (!useQwenSwitch) end

        // ---- 分组 3：推理诊断 ----
        item(key = "diag-group") {
            SectionHeader(title = "🔍 推理诊断（闪退 / 没输出时点这里）")
        }
        item(key = "diag-card") {
            DiagnosticCard(
                lines = diagLines,
                running = diagRunning,
                onStart = {
                    diagLines.clear()
                    diagRunning = true
                    addLog("══════════════════ 开始诊断 ══════════════════")
                    addLog("当前引擎模式：${if (useQwenSwitch) "Qwen 极简推理器 (beta)" else "Llama 引擎 (b5180)"}")
                    scope.launch {
                        runDiagnosticImpl(
                            addLog = ::addLog,
                            selectedModel = selectedModel,
                            engineSnapshot = engineSnapshot,
                            engine = App.instance.llamaEngineRef(),
                            onModelNeedLoad = { m ->
                                val app = App.instance
                                if (useQwenSwitch) {
                                    app.modelManager.switchAndLoadQwenModel(m.id, app.qwenEngineRef())
                                } else {
                                    val holder = LlamaEngineHolder { app.llamaEngineRef() }
                                    app.modelManager.switchAndLoadModel(m.id, holder)
                                }
                            }
                        )
                        diagRunning = false
                        addLog("══════════════════ 诊断结束 ══════════════════")
                    }
                },
                onCancel = {
                    // 取消时两边都调
                    runCatching { App.instance.llamaEngineRef().cancel() }
                    runCatching { App.instance.qwenEngineRef().cancel() }
                    addLog("用户请求取消当前推理")
                },
                // —— v1.3.6 新增：纯手机端的抓日志和分享，不需要电脑/adb/root ——
                onGrabLogcat = {
                    scope.launch {
                        addLog("")
                        addLog("══════════ 📥 抓取 LlamaJni 设备日志（手机端 logcat -d） ══════════")
                        addLog("说明：Android 5+ 允许每个 App 读取自己 UID 产生的 logcat 条目，无需任何权限。")
                        val grabbed = runCatching { grabLlamaJniLogcatImpl() }
                        if (grabbed.isFailure) {
                            addLog("❌ 抓日志失败：${grabbed.exceptionOrNull()?.message ?: grabbed.exceptionOrNull()?.toString() ?: "unknown"}")
                        } else {
                            val lines = grabbed.getOrThrow()
                            if (lines.isEmpty()) {
                                addLog("⚠️  没抓到任何 LlamaJni / LlamaJniEngine 条目。")
                                addLog("    → 请先：① 点模型卡「🔄 重新加载到内存」② 或在聊天页发一条消息，再回来点一次这个按钮")
                            } else {
                                addLog("✅ 抓到 ${lines.size} 行（只显示最后 120 行，完整内容点「📤 分享诊断包」导出）：")
                                addLog("")
                                val tail = lines.takeLast(120)
                                tail.forEach { addLog(it) }
                                // 同时把全部 lines 存到一个临时缓存，分享时一起带上
                                lastGrabLogcatFull = lines
                            }
                        }
                    }
                },
                onShareAll = {
                    val ctx = App.instance
                    val app = App.instance
                    val header = buildString {
                        appendLine("AI编程助手诊断包 — v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                        appendLine("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
                        appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
                        appendLine("ABI：${android.os.Build.SUPPORTED_ABIS.joinToString("/")}")
                        appendLine("总内存：${runCatching { val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager; val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }; "${mi.totalMem / 1024 / 1024}MB (avail=${mi.availMem / 1024 / 1024}MB, lowMemory=${mi.lowMemory})" }.getOrDefault("n/a")}")
                        appendLine("CPU_ABI2：${android.os.Build.CPU_ABI2}（arm64-v8a 必为空）")
                        appendLine()
                        appendLine("═══════════════════════════════════════════")
                        appendLine("当前激活引擎：${if (QwenInferEngine.useQwenEngine) "QwenInferEngine(极简自写beta)" else "LlamaJniEngine(b5180)"}；模拟模式=${LlamaJniEngine.forceMockMode}")
                        appendLine("当前模型：${app.modelManager.lastLoadedPath() ?: "<未加载>"}")
                        appendLine("—— Llama 引擎 ——")
                        val llamaSt = LlamaJniEngine.libStatus()
                        appendLine("  libLoaded=${llamaSt.first}  libErr=${llamaSt.second ?: "无"}")
                        val llama = app.llamaEngineRef()
                        appendLine("  ctx=0x${llama.currentCtx().toString(16)}  lastLoadErr=${llama.lastLoadError() ?: "<无>"}")
                        appendLine("—— Qwen 引擎（v1.3.24 beta）——")
                        val qwenSt = QwenInferEngine.libStatus()
                        appendLine("  libLoaded=${qwenSt.first}  libErr=${qwenSt.second ?: "无"}")
                        val qwen = app.qwenEngineRef()
                        appendLine("  modelLoaded=${qwen.isModelLoaded()}  lastLoadErr=${qwen.lastLoadError() ?: "<无>"}")
                        appendLine("═══════════════════════════════════════════")
                        appendLine()
                    }
                    val fullLogcat = lastGrabLogcatFull.joinToString("\n")
                    val diagBox = diagLines.joinToString("\n")
                    val content = buildString {
                        append(header)
                        appendLine("==== 诊断框（页面黑底日志框）内容 ====")
                        appendLine(diagBox)
                        appendLine()
                        if (fullLogcat.isNotBlank()) {
                            appendLine("==== 抓设备日志 logcat -d 全部（${lastGrabLogcatFull.size} 行，含 LlamaJni/qwen-core） ====")
                            appendLine(fullLogcat)
                        } else {
                            appendLine("==== 抓设备日志 logcat -d （未抓，可在诊断卡先点「📥 抓设备日志」） ====")
                        }
                        appendLine()
                        appendLine("==== crash_log.txt（如果有崩溃的话，App.instance.getExternalFilesDir(null)/crash_log.txt） ====")
                        runCatching {
                            val dir: File? = ctx.getExternalFilesDir(null)
                            val crashF = File(dir, "crash_log.txt")
                            if (crashF.exists()) crashF.readText() else "（不存在 / 本次还没崩过）"
                        }.onSuccess { append(it) }
                            .onFailure { append("读取失败：${it.message}") }
                    }
                    runCatching {
                        val dir: File? = ctx.externalCacheDir
                        val outFile = File(dir, "ai-coder-diag-${System.currentTimeMillis()}.txt")
                        outFile.writeText(content)
                        val uri: Uri = FileProvider.getUriForFile(
                            ctx, "${BuildConfig.APPLICATION_ID}.fileprovider", outFile
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "AI编程助手诊断包 v${BuildConfig.VERSION_NAME}")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "分享诊断包给…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { t ->
                        Toast.makeText(ctx, "分享失败：${t.message ?: t}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // ---- Build 信息 ----
        item(key = "build-info") {
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
    // 🔴 v1.3.7 魅族修复：collectAsStateWithLifecycle 替换 collectAsState，避免进入/离开 Settings Tab
    //    时因为无生命周期感知导致的「collect 已取消但 Compose 还在重组」的 NPE/崩溃。
    val themeMode by themeStore.themeModeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.FOLLOW_SYSTEM)

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

            // 🔴 操作按钮放在【内存状态条之前】，避免卡片处于屏幕底部时按钮被截断看不到。
            //    用户反馈「好像没有整顿（诊断/重加载）的按键」就是因为按钮在最下面一行、屏幕外。
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

            Spacer(Modifier.height(8.dp))

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
                if (status.ctx == -1L) "✓ 已加载到内存  ·  Qwen 引擎驻留"
                else "✓ 已加载到内存  ·  ctx=0x${status.ctx.toString(16)}",
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

// ===================================================================
// 诊断卡片：按钮 + 实时日志（自动滚动到底）
// ===================================================================
@Composable
private fun DiagnosticCard(
    lines: List<String>,
    running: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onGrabLogcat: () -> Unit,    // 📥 手机端抓 LlamaJni 日志
    onShareAll: () -> Unit      // 📤 一键把诊断日志 + 探针日志 写文件唤起分享菜单
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "最小推理自检",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "跑一次超短提问（\"你好\"），把每一步写在这里。\n" +
                            "闪退 / 一个字出不来时，跑完截图发给开发者即可，不用再抓 logcat。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
                if (!running) {
                    Button(
                        onClick = onStart,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            Icons.Outlined.BugReport, null,
                            Modifier.padding(end = 4.dp)
                        )
                        Text("开始诊断", fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            Icons.Outlined.PauseCircle, null,
                            Modifier.padding(end = 4.dp),
                            tint = Color(0xFFC24141)
                        )
                        Text("取消", fontSize = 12.sp, color = Color(0xFFC24141))
                    }
                }
            }

            // —— 🆕 v1.3.6 手机端专用按钮（纯手机就能抓日志/分享，不需要电脑/adb/root）——
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val ctx2 = androidx.compose.ui.platform.LocalContext.current
                FilledTonalButton(
                    onClick = onGrabLogcat,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp),
                    enabled = !running
                ) {
                    Icon(Icons.Outlined.ReceiptLong, null, Modifier.padding(end = 4.dp))
                    Text("📥 抓LlamaJni日志", fontSize = 11.5.sp)
                }
                OutlinedButton(
                    onClick = onShareAll,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Outlined.IosShare, null, Modifier.padding(end = 4.dp))
                    Text("📤 分享诊断包", fontSize = 11.5.sp)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        val txt = lines.joinToString("\n")
                        if (txt.isBlank()) {
                            Toast.makeText(ctx2, "日志框为空，先点「开始诊断」或「抓LlamaJni日志」", Toast.LENGTH_SHORT).show()
                            return@FilledTonalButton
                        }
                        val cm = ctx2.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("ai-coder-diagnostic", txt))
                        Toast.makeText(ctx2, "已复制到剪贴板（约${txt.length}字）", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.padding(end = 4.dp))
                    Text("复制全部", fontSize = 11.5.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // 日志输出区：等宽字体 + 可滚动 + 深色背景
            // 🔴🔴🔴 v1.3.7 魅族修复（最高优先级）：
            //    之前这里用的是 Column(Modifier.verticalScroll(...))，
            //    而它本身又处在整个 SettingsPage 的根 LazyColumn(垂直滚动) 里。
            //    → 荣耀平板 / 模拟器侥幸不崩，但魅族 20 的 Compose Runtime 对
            //      「同向嵌套滚动 + 父测量是 Inf/AtMost」检查更严格，点设置 Tab 直接崩。
            //    根治方案：
            //      1) 日志区内部不用 verticalScroll，改用 LazyColumn(同样是垂直滚动 Composable，
            //         但 LazyColumn 内部高度是 bounded 测量，能过魅族的 runtime 检查)。
            //      2) 给外层 OutlinedCard 加一个 heightIn 上限，保证子项测量有界，
            //         避免「父 height=AtMost Inf → 子 verticalScroll 报
            //           IllegalStateException: Vertically scrollable component was measured with
            //           an infinity maximum height」的厂商 ROM 特有崩溃。
            // 🔴 注意：rememberLazyListState / LaunchedEffect 必须在 Composable 作用域里，
            //    不能写在 LazyColumn { items {...} } DSL 块里（那不是 Composable 上下文）。
            val diagLogListState = rememberLazyListState()
            androidx.compose.runtime.LaunchedEffect(lines.size) {
                if (lines.isNotEmpty()) runCatching {
                    // 避开 animateScrollTo：在某些厂商 ROM 下 Compose 重组会打断动画协程，
                    // 导致 IllegalStateException。直接 scrollToItem 不做动画，稳为上。
                    val idx = if (lines.lastIndex >= 0) lines.lastIndex else 0
                    diagLogListState.scrollToItem(idx)
                }
            }
            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF0E1013)  // 近黑板子
                ),
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 220.dp)
                    // 🔴 关键：给一个最大高度约束，让内部 LazyColumn 的测量不是无穷大
                    .heightIn(max = 420.dp)
            ) {
                LazyColumn(
                    state = diagLogListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (lines.isEmpty()) {
                        item(key = "empty-hint") {
                            Text(
                                "（还没跑过。点右上角「开始诊断」。\n" +
                                    "结果会按时间顺序从上到下显示。）",
                                fontSize = 11.sp,
                                color = Color(0xFF7A7D82),
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 15.sp
                            )
                        }
                    } else {
                        items(lines.size, key = { i -> "diag-line-$i" }) { i ->
                            val line = lines[i]
                            Text(
                                text = line,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = when {
                                    "❌" in line -> Color(0xFFFF9A9A)
                                    "✅" in line || "✓" in line -> Color(0xFF92E3A9)
                                    "⚠️" in line || "WARN" in line -> Color(0xFFFFD08A)
                                    "══════" in line -> Color(0xFF6FA8D8)
                                    else -> Color(0xFFD7D9DD)
                                },
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ===================================================================
// 🆕 v1.3.6 手机端专用：纯 JVM 调用 logcat -d 抓 LlamaJni/LlamaJniEngine tag 的日志
//   关键：Android 5+ / UID 隔离下，每个 App 默认能读到「自己 UID 产生的 logcat 条目」，
//        不需要 READ_LOGS 权限、不需要 root、不需要电脑/adb。
//   -s tag 只看相关标签，避免一次拉几万行卡住；-d 一次性 dump 不阻塞。
// ===================================================================
private suspend fun grabLlamaJniLogcatImpl(): List<String> = withContext(kotlinx.coroutines.Dispatchers.IO) {
    val pb = ProcessBuilder(
        "logcat", "-d", "-v", "threadtime",
        "-s",
        "LlamaJni:V",
        "LlamaJniEngine:V",
        "qwen-jni:V",       // v1.3.24+ 极简 Qwen 推理器 JNI 桥日志
        "qwen-core:V",      // v1.3.24+ Qwen 推理核心诊断日志
        "QwenInferEngine:V",// v1.3.24+ Qwen Kotlin 引擎日志
        "DEBUG:*",          // 系统崩溃记录（SIGSEGV/tombstone 的开头几行常打在 DEBUG tag）
        "AndroidRuntime:E",
        "ActivityManager:I"
    )
        .redirectErrorStream(true)
    val proc: Process = pb.start()
    val stdout: String = proc.inputStream.bufferedReader(java.nio.charset.StandardCharsets.UTF_8).use { it.readText() }
    runCatching {
        if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
    }
    val lines = stdout.lineSequence()
        .map { it.trimEnd() }
        .filterNot { it.isEmpty() }
        .toList()
    lines
}

// ===================================================================
// 诊断逻辑实现（挂起函数，在协程里跑；按步骤 addLog 写 UI 上的日志框）
// ===================================================================
private suspend fun runDiagnosticImpl(
    addLog: (String) -> Unit,
    selectedModel: ModelEntity?,
    engineSnapshot: LoadDiagSnapshot,
    engine: LlamaJniEngine?,
    onModelNeedLoad: suspend (ModelEntity) -> Pair<Boolean, String>
) {
    val tStart = System.currentTimeMillis()
    // ---- 1. 环境/基础信息 ----
    val rt = Runtime.getRuntime()
    val freeMB = rt.freeMemory() / 1024 / 1024
    val totalMB = rt.totalMemory() / 1024 / 1024
    val maxMB = rt.maxMemory() / 1024 / 1024
    addLog("① JVM Heap: free=${freeMB}MB total=${totalMB}MB max=${maxMB}MB")
    addLog("② lib 状态: loaded=${engineSnapshot.libLoadedOk}  err=${engineSnapshot.libLoadError?:"(无)"}")
    val ctxStr = when(engineSnapshot.currentCtx) {
        -1L -> "Qwen 引擎已驻留（单例模型，无 ctx 指针）"
        0L -> "0（模型未驻留内存）"
        else -> "0x${java.lang.Long.toHexString(engineSnapshot.currentCtx)}"
    }
    addLog("③ 引擎状态: $ctxStr  lastLoadErr=${engineSnapshot.lastLoadError?:"(无)"}")
    // 🔴 v1.3.24：如果引擎是 Qwen 极简推理器，诊断函数目前只完整支持 Llama（最小推理第 4 步会调 Llama chatFlow）。
    //   前 3 步（环境/模型/加载）对 Qwen 有效，第 4 步会自动 fallback 到 Llama 跑最小推理作链路体检。
    if (engineSnapshot.currentCtx == -1L) {
        addLog("ℹ️  当前处于 Qwen 极简推理器模式：第 1~3 步诊断正常，第 4 步会用 Llama 引擎跑最小推理（作链路完整性体检）")
    }

    // ---- 2. 模型存在性 ----
    if (selectedModel == null) {
        addLog("❌ 还没有「当前模型」。请先在上面「导入 GGUF 模型」，然后点「设为当前并加载到内存」")
        return
    }
    addLog("④ 当前模型: ${selectedModel.displayName}  size=${selectedModel.sizeBytes/1024/1024}MB  validated=${selectedModel.validated}  path=${selectedModel.filePath}")
    val f = java.io.File(selectedModel.filePath)
    if (!f.exists()) {
        addLog("❌ 模型文件路径不存在：${selectedModel.filePath}。请删除后重新导入。")
        return
    }

    // ---- 3. 确保引擎/模型已加载 ----
    // v1.3.24：引擎可能是 Llama(ctx=Long ptr) 或 Qwen(ctx=-1 哨兵表示已驻留)，用 snapshot 综合判断
    val snapCtx = engineSnapshot.currentCtx
    val llamaCtx = engine?.currentCtx() ?: 0L
    var alreadyLoaded = snapCtx != 0L   // Llama: ptr != 0; Qwen: -1
    if (!alreadyLoaded) {
        addLog("⚠️ 模型还没加载到内存。尝试加载中（可能 10~30 秒，3B Q4_K_M 约 2.1GB）...")
        val (ok, tip) = onModelNeedLoad(selectedModel)
        addLog(if (ok) "✅ 加载完成：$tip" else "❌ 加载失败：$tip")
        if (!ok) {
            addLog("❌ 加载失败，无法开始推理。常见原因：\n" +
                "  · 内存不足 4GB → 关后台/重启手机后重试\n" +
                "  · GGUF 文件损坏 → 删了重下\n" +
                "  · 安装包架构不对 → 必须是 arm64-v8a APK\n" +
                "  · Qwen 模式：仅支持 Qwen2.5-1.5B-Instruct Q4_K_M（3B/其他量化暂不支持）")
            return
        }
    } else {
        val mark = if (snapCtx == -1L) "Qwen 引擎" else "ctx=0x${java.lang.Long.toHexString(snapCtx)}"
        addLog("✅ 模型已驻留内存（$mark），直接开始诊断")
    }

    // ---- 诊断最小推理部分使用 Llama 引擎 ----
    // （初版 Qwen 推理核心不在诊断内跑，因为 Llama 的最小推理诊断已经稳定且包含超时/错误收集。
    //   如果用户要测试 Qwen 真推理：回到聊天页直接发消息即可。）
    if (engine == null) {
        addLog("❌ Llama 引擎引用为空（极端初始化错误）。请重启 App 后重试。")
        return
    }
    if (llamaCtx == 0L) {
        addLog("ℹ️  Llama 引擎内存里还没模型（当前用的是 Qwen 模式），最小推理步骤跳过。\n" +
            "   如需测试 Qwen 真推理：切回聊天页直接提问即可。")
        addLog("══════════════════ 诊断结束 ══════════════════（跳过 Llama 最小推理）")
        return
    }

    // ---- 4. 跑一次最小推理：system=超短 + user="你好，请回复" ----
    addLog("⑤ 开始最小推理：system=\"短答助手\"  user=\"你好，请回复两个字。\"（首 token 最长等待 45 秒）")
    val sb = StringBuilder()
    var tokenCount = 0
    var prefillDone = false
    val tInfer0 = System.currentTimeMillis()
    try {
        engine.chatFlow(
            system = "你是一个简短的中文助手，只回答一两个字。",
            user = "你好，请回复两个字。"
        ).collectLatest { chunk ->
            when (chunk) {
                is ChatChunk.PrefillProgress -> {
                    if (!prefillDone) {
                        addLog("   预填充中... ${chunk.consumed}/${chunk.total} token (${chunk.percent}%)")
                        if (chunk.percent >= 100) prefillDone = true
                    }
                }
                is ChatChunk.Token -> {
                    if (!prefillDone) {
                        prefillDone = true
                        val ms = System.currentTimeMillis() - tInfer0
                        addLog("✅ 首 token 到达！耗时 ${ms}ms。接下来显示持续 token 及累计：")
                    }
                    sb.append(chunk.text)
                    tokenCount++
                    if (tokenCount <= 12 || tokenCount % 10 == 0) {
                        addLog("   · token #$tokenCount  累计=\"${sb.toString().take(48)}\"")
                    }
                }
                is ChatChunk.Done -> {
                    val ms = System.currentTimeMillis() - tInfer0
                    addLog("✅ 完成。stop=\"${chunk.stopReason}\"  total_tokens=$tokenCount  耗时=${ms}ms  速度=${"%.1f".format(if(ms>0) tokenCount*1000.0/ms else 0.0)} tok/s")
                    addLog("   最终回复全文=\"${chunk.full.take(200)}\"")
                    if (tokenCount == 0) {
                        addLog("❌❌ 0 token 输出！这对应你说的「一个字蹦不出来」。\n" +
                            "  → 根因：tokenize 开头错位（多余 BOS 或 <|im_start|> 当普通字符拆开），模型一上来就 sample EOS。\n" +
                            "  → 新版本 v1.3.4 已修 add_special=0 parse_special=1 并加 BOS 自动剥离。若仍 0 token，请把上面的日志 + logcat 中 tag=LlamaJNI 的行一起发给开发者。")
                    }
                }
                is ChatChunk.Error -> {
                    val ms = System.currentTimeMillis() - tInfer0
                    addLog("❌ 推理错误 @ ${ms}ms：${chunk.hint.take(400)}")
                    addLog("   Throwable: ${chunk.t.javaClass.simpleName} - ${chunk.t.message}")
                }
            }
        }
    } catch (t: Throwable) {
        addLog("❌ collectLatest 抛异常：${t.javaClass.simpleName} - ${t.message}")
    }
    val total = System.currentTimeMillis() - tStart
    addLog("⑥ 整个诊断流程总耗时：${total}ms")
}

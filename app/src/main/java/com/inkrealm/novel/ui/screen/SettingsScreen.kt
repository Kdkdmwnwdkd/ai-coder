package com.inkrealm.novel.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkrealm.novel.data.model.UserSettings
import com.inkrealm.novel.ui.theme.OnBackgroundLight
import com.inkrealm.novel.ui.theme.PrimaryLight
import com.inkrealm.novel.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()

    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var apiBase by remember { mutableStateOf(settings.apiBase) }
    var modelName by remember { mutableStateOf(settings.modelName) }
    var demoMode by remember { mutableStateOf(settings.demoMode) }
    var enableKb by remember { mutableStateOf(settings.enableKb) }
    var embedKey by remember { mutableStateOf(settings.embeddingKey) }
    var embedUrl by remember { mutableStateOf(settings.embeddingUrl) }
    var embedModel by remember { mutableStateOf(settings.embeddingModel) }
    var embedDim by remember { mutableStateOf(settings.embeddingDim.toString()) }
    var enableSummary by remember { mutableStateOf(settings.enableSummary) }
    var enableEntity by remember { mutableStateOf(settings.enableEntity) }
    var pollinationsKey by remember { mutableStateOf(settings.pollinationsKey) }

    LaunchedEffect(settings) {
        apiKey = settings.apiKey; apiBase = settings.apiBase; modelName = settings.modelName
        demoMode = settings.demoMode; enableKb = settings.enableKb
        embedKey = settings.embeddingKey; embedUrl = settings.embeddingUrl
        embedModel = settings.embeddingModel; embedDim = settings.embeddingDim.toString()
        enableSummary = settings.enableSummary; enableEntity = settings.enableEntity
        pollinationsKey = settings.pollinationsKey
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", color = OnBackgroundLight) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackgroundLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())
        ) {
            SectionTitle("AI 文本模型")
            InfoText("Android 端使用 OpenAI 兼容协议直接调用，请选择活跃配置。")
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = apiBase, onValueChange = { apiBase = it }, label = { Text("API URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = modelName, onValueChange = { modelName = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("演示模式（无 API Key 时用内置模拟）", color = OnBackgroundLight)
                Switch(checked = demoMode, onCheckedChange = { demoMode = it }, colors = SwitchDefaults.colors(checkedThumbColor = PrimaryLight))
            }

            Divider(Modifier.padding(vertical = 16.dp))

            SectionTitle("图像生成引擎")
            InfoText("Pollinations 走云端，无需本地环境；ComfyUI 连接你本地/局域网运行的 ComfyUI。")
            OutlinedTextField(value = pollinationsKey, onValueChange = { pollinationsKey = it }, label = { Text("Pollinations Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Divider(Modifier.padding(vertical = 16.dp))

            SectionTitle("本地知识库（实验性）")
            InfoText("开启后，保存章节时正文会被切片并通过 Embedding 模型转为向量本地存储；生成新章节时自动检索历史中最相关的片段，拼到提示词末尾作为「长程相关记忆」。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("启用本地知识库", color = OnBackgroundLight)
                Switch(checked = enableKb, onCheckedChange = { enableKb = it }, colors = SwitchDefaults.colors(checkedThumbColor = PrimaryLight))
            }

            SectionTitle("Embedding 服务（OpenAI 兼容）")
            InfoText("默认指向阿里云百炼（DashScope）。也支持任意 OpenAI 兼容的 Embedding 端点。")
            OutlinedTextField(value = embedKey, onValueChange = { embedKey = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = embedUrl, onValueChange = { embedUrl = it }, label = { Text("API URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = embedModel, onValueChange = { embedModel = it }, label = { Text("模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = embedDim, onValueChange = { embedDim = it }, label = { Text("维度") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Divider(Modifier.padding(vertical = 16.dp))

            SectionTitle("增强层（分层摘要 + 实体抽取）")
            InfoText("这两层增强会在保存章节时额外调用一次文本模型，并在生成新章节时把「全书梗概 / 当前弧线进度 / 未回收伏笔」塞进提示词。建议长篇（30 章以上）开启。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用分层摘要", color = OnBackgroundLight)
                    Text("章节 / 弧线 / 全书", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
                Switch(checked = enableSummary, onCheckedChange = { enableSummary = it }, colors = SwitchDefaults.colors(checkedThumbColor = PrimaryLight))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用实体抽取", color = OnBackgroundLight)
                    Text("人物登场 / 伏笔 / 地点 / 事件 / 物品", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                }
                Switch(checked = enableEntity, onCheckedChange = { enableEntity = it }, colors = SwitchDefaults.colors(checkedThumbColor = PrimaryLight))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    viewModel.updateSettings(
                        UserSettings(
                            apiKey = apiKey, apiBase = apiBase, modelName = modelName, demoMode = demoMode,
                            enableKb = enableKb, embeddingKey = embedKey, embeddingUrl = embedUrl,
                            embeddingModel = embedModel, embeddingDim = embedDim.toIntOrNull() ?: 1024,
                            enableSummary = enableSummary, enableEntity = enableEntity,
                            pollinationsKey = pollinationsKey
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight)
            ) { Text("保存设置") }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = PrimaryLight, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
}

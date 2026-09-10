package com.inkrealm.novel.ui.screen

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inkrealm.novel.service.AiService
import com.inkrealm.novel.ui.theme.OnBackgroundLight
import com.inkrealm.novel.ui.theme.PrimaryLight
import com.inkrealm.novel.ui.theme.SurfaceVariant
import com.inkrealm.novel.ui.viewmodel.ChapterEditViewModel
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditScreen(
    chapterId: Long,
    workId: Long,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: ChapterEditViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChapterEditViewModel(context.applicationContext as Application, chapterId, workId) as T
            }
        }
    )

    val chapter by viewModel.chapter.collectAsState()
    val isAiLoading by viewModel.isAiLoading.collectAsState()
    val aiResult by viewModel.aiResult.collectAsState()

    val tts = remember { TextToSpeech(context) { } }
    var isSpeaking by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf(chapter?.content ?: "") }

    LaunchedEffect(chapter) { chapter?.let { content = it.content } }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chapter?.title ?: "编辑章节", color = OnBackgroundLight, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackgroundLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (isSpeaking) {
                        IconButton(onClick = { tts.stop(); isSpeaking = false }) {
                            Icon(Icons.Default.Stop, contentDescription = "停止", tint = PrimaryLight)
                        }
                    } else {
                        IconButton(onClick = {
                            if (content.isNotBlank()) {
                                isSpeaking = true
                                val locale = if (tts.isLanguageAvailable(Locale.CHINESE) >= 0) Locale.CHINESE else Locale.US
                                tts.language = locale
                                tts.speak(content, TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "朗读", tint = OnBackgroundLight)
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.background) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    AiActionButton(Icons.Default.AutoFixHigh, "续写") { viewModel.aiContinue() }
                    AiActionButton(Icons.Default.Brush, "润色") { viewModel.aiPolish() }
                    AiActionButton(Icons.Default.Expand, "扩写") { viewModel.aiExpand() }
                    AiActionButton(Icons.Default.Lightbulb, "灵感") { viewModel.aiIdea() }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isAiLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PrimaryLight)
            aiResult?.let { result ->
                AiResultPanel(
                    result = result,
                    onApply = { viewModel.applyAiResult(); content = viewModel.chapter.value?.content ?: content },
                    onDismiss = { viewModel.clearAiResult() }
                )
            }
            OutlinedTextField(
                value = content,
                onValueChange = { content = it; viewModel.updateContent(it) },
                placeholder = { Text("开始创作...", color = OnSurfaceVariant) },
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, color = OnBackgroundLight),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedBorderColor = PrimaryLight,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

@Composable
fun AiActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, contentDescription = label, tint = PrimaryLight, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = PrimaryLight)
    }
}

@Composable
fun AiResultPanel(result: String, onApply: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("AI 生成结果", style = MaterialTheme.typography.titleMedium, color = PrimaryLight)
            Spacer(modifier = Modifier.height(8.dp))
            Text(result, style = MaterialTheme.typography.bodyMedium, color = OnBackgroundLight, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", color = OnSurfaceVariant) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onApply, colors = ButtonDefaults.buttonColors(containerColor = PrimaryLight)) { Text("应用到正文") }
            }
        }
    }
}

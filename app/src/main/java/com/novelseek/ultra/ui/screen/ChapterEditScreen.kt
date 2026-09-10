package com.novelseek.ultra.ui.screen

import android.app.Application
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
import com.novelseek.ultra.service.TtsService
import com.novelseek.ultra.ui.theme.Accent
import com.novelseek.ultra.ui.theme.CardSurface
import com.novelseek.ultra.ui.theme.OnBackground
import com.novelseek.ultra.ui.viewmodel.ChapterEditViewModel
import kotlinx.coroutines.launch

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
                return ChapterEditViewModel(
                    context.applicationContext as Application,
                    chapterId,
                    workId
                ) as T
            }
        }
    )

    val chapter by viewModel.chapter.collectAsState()
    val isAiLoading by viewModel.isAiLoading.collectAsState()
    val aiResult by viewModel.aiResult.collectAsState()
    val error by viewModel.error.collectAsState()

    val ttsService = remember { TtsService(context) }
    var isSpeaking by remember { mutableStateOf(false) }

    var content by remember { mutableStateOf(chapter?.content ?: "") }
    var title by remember { mutableStateOf(chapter?.title ?: "") }

    LaunchedEffect(chapter) {
        chapter?.let {
            content = it.content
            title = it.title
        }
    }

    DisposableEffect(Unit) {
        onDispose { ttsService.shutdown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, color = OnBackground, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    if (isSpeaking) {
                        IconButton(onClick = {
                            ttsService.stop()
                            isSpeaking = false
                        }) {
                            Icon(Icons.Default.Stop, contentDescription = "停止朗读", tint = Accent)
                        }
                    } else {
                        IconButton(onClick = {
                            if (content.isNotBlank()) {
                                isSpeaking = true
                                ttsService.speak(content) {
                                    isSpeaking = false
                                }
                            }
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "朗读", tint = OnBackground)
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    AiActionButton(
                        icon = Icons.Default.AutoFixHigh,
                        label = "续写",
                        onClick = { viewModel.aiContinue() },
                        enabled = !isAiLoading
                    )
                    AiActionButton(
                        icon = Icons.Default.Brush,
                        label = "润色",
                        onClick = { viewModel.aiPolish() },
                        enabled = !isAiLoading
                    )
                    AiActionButton(
                        icon = Icons.Default.Expand,
                        label = "扩写",
                        onClick = { viewModel.aiExpand() },
                        enabled = !isAiLoading
                    )
                    AiActionButton(
                        icon = Icons.Default.Lightbulb,
                        label = "灵感",
                        onClick = { viewModel.aiIdea() },
                        enabled = !isAiLoading
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isAiLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent
                )
            }

            aiResult?.let { result ->
                AiResultPanel(
                    result = result,
                    onApply = { viewModel.applyAiResult() },
                    onDismiss = { viewModel.clearAiResult() }
                )
            }

            error?.let { err ->
                Surface(
                    color = Accent.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = Accent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(err, color = Accent, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                LaunchedEffect(err) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.clearError()
                }
            }

            OutlinedTextField(
                value = content,
                onValueChange = {
                    content = it
                    viewModel.updateContent(it)
                },
                placeholder = { Text("开始创作你的故事...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 28.sp,
                    color = OnBackground
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

@Composable
fun AiActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) Accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun AiResultPanel(
    result: String,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "AI 生成结果",
                style = MaterialTheme.typography.titleMedium,
                color = Accent
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                result,
                style = MaterialTheme.typography.bodyMedium,
                color = OnBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onApply,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("应用到正文")
                }
            }
        }
    }
}

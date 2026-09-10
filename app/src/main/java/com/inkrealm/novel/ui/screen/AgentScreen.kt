package com.inkrealm.novel.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.inkrealm.novel.ui.theme.OnBackgroundLight
import com.inkrealm.novel.ui.theme.PrimaryLight
import com.inkrealm.novel.ui.theme.OnSurfaceVariant
import com.inkrealm.novel.ui.theme.SurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(onBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<Pair<Boolean, String>>(false to "我可以帮你完整地操作这个软件：一句话生成整本小说、修改某副本/弧线/章节、检索内容、审阅前后矛盾、联网搜索等。关键步骤我会先和你确认。试试：「帮我新建一个玄幻长篇并生成大纲」。")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能体", color = OnBackgroundLight) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackgroundLight)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("告诉智能体要做什么...", color = OnSurfaceVariant) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryLight)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (input.isNotBlank()) {
                            messages = messages + (true to input)
                            val userInput = input
                            input = ""
                            messages = messages + (false to "【演示模式】收到指令：$userInput\n\n目前智能体处于演示状态。配置 API Key 后可调用真实 AI 进行完整操作。")
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "发送", tint = PrimaryLight)
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Surface(shape = RoundedCornerShape(12.dp), color = PrimaryLight, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("AI", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("智能体", style = MaterialTheme.typography.titleMedium, color = OnBackgroundLight)
                        Text("待命", style = MaterialTheme.typography.bodySmall, color = OnSurfaceVariant)
                    }
                }
            }
            items(messages.size) { index ->
                val (isUser, msg) = messages[index]
                ChatBubble(isUser = isUser, message = msg)
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ChatBubble(isUser: Boolean, message: String) {
    val bg = if (isUser) PrimaryLight else SurfaceVariant
    val color = if (isUser) MaterialTheme.colorScheme.onPrimary else OnBackgroundLight
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bg,
            modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 320.dp)
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

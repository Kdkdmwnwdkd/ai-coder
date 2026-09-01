package com.xuedi.coder.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.xuedi.coder.action.ActionExecutor
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.vm.ChatViewModel

@Composable
fun ChatPage(vm: ChatViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val isTyping by vm.isTyping.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current

    LaunchedEffect(messages.size, isTyping) {
        val idx = (messages.size - 1).coerceAtLeast(0)
        runCatching { listState.animateScrollToItem(idx) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.role == ChatRole.User) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        modifier = Modifier.widthIn(max = 320.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (msg.role) {
                                ChatRole.User -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ChatRole.Assistant -> MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                                else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                            }
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = when (msg.role) {
                                    ChatRole.User -> "我"
                                    ChatRole.Assistant -> "AI 编程助手"
                                    ChatRole.Error -> "❌ 错误"
                                    ChatRole.System -> "System"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = msg.content.ifBlank { " " },
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (msg.actions.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(msg.actions, key = { it.raw }) { act ->
                                        OutlinedButton(
                                            onClick = {
                                                val res = ActionExecutor.executeAll(ctx, listOf(act))
                                                val t = if (res.second == null)
                                                    "✅ ${ActionExecutor.friendlyName(act.name)}"
                                                else
                                                    "❌ ${res.second}"
                                                Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Text(ActionExecutor.friendlyName(act.name), fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isTyping) {
                item {
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier
                                .padding(8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .height(16.dp)
                                        .padding(end = 10.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "AI 正在思考并打字...",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 输入框
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入你想写的代码 / 问题 / 需求...", color = Color.Gray) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                maxLines = 5
            )
            IconButton(
                onClick = {
                    val txt = input
                    if (txt.isNotBlank()) {
                        input = ""
                        vm.sendMessage(txt)
                    }
                }
            ) {
                Icon(Icons.Outlined.Send, "发送", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

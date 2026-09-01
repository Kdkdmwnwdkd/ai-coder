package com.xuedi.coder.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import kotlinx.coroutines.launch
import com.xuedi.coder.action.ActionExecutor
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.vm.ChatViewModel

/**
 * 【M8 多话题】ChatPage：左侧 ModalNavigationDrawer 显示话题列表 + 右侧当前话题消息。
 *
 * 顶部汉堡按钮（Menu icon）→ 打开 Drawer；Drawer 顶部有「+ 新对话」按钮；
 * 话题项可点击切换；长按或末尾按钮支持重命名 / 删除（简化版：每个话题项右侧 2 个图标按钮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPage(vm: ChatViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val isTyping by vm.isTyping.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val currentTopicId by vm.currentTopicId.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val ctx = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 重命名 / 删除 对话框状态
    var renamingTopic by remember { mutableStateOf<Pair<String, String>?>(null) }  // (id, oldTitle)
    var deletingTopic by remember { mutableStateOf<Pair<String, String>?>(null) } // (id, title)

    DisposableEffect(vm) {
        onDispose { vm.cancelInference() }
    }

    LaunchedEffect(messages.size, isTyping) {
        val idx = (messages.size - 1).coerceAtLeast(0)
        runCatching { listState.animateScrollToItem(idx) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ) {
                // Drawer 顶部：标题 + 「+ 新对话」按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "对话列表",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = {
                        vm.newTopic()
                        scope.launch { drawerState.close() }
                    }) {
                        Icon(Icons.Outlined.Add, "新建对话", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(4.dp))

                // 话题列表
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(topics, key = { it.id }) { topic ->
                        val isCurrent = topic.id == currentTopicId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .background(
                                    if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 左侧：话题标题 + 时间（点文字区域切换话题）
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                                    .clickable {
                                        vm.switchTopic(topic.id)
                                        scope.launch { drawerState.close() }
                                    }
                            ) {
                                Text(
                                    topic.title,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    "消息于 ${formatTime(topic.lastActiveMs)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 右侧：重命名 / 删除按钮（IconButton 自己消费点击，不冒泡到左侧）
                            Row {
                                IconButton(onClick = { renamingTopic = topic.id to topic.title }) {
                                    Icon(Icons.Outlined.Edit, "重命名",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.height(18.dp))
                                }
                                IconButton(onClick = { deletingTopic = topic.id to topic.title }) {
                                    Icon(Icons.Outlined.Delete, "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.height(18.dp))
                                }
                            }
                        }
                    }
                    if (topics.isEmpty()) {
                        item {
                            Text(
                                "暂无对话。点右上 + 新建一个吧。",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // 顶部条：汉堡（开话题Drawer）+ 中间当前话题名（不再用"AI编程助手"做大标题，而是和 TRAE 一样用话题名/「新对话」）+ 右侧➕新对话
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Outlined.Menu, "话题列表", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    modifier = Modifier.weight(1f),
                    text = topics.firstOrNull { it.id == currentTopicId }?.title ?: "新对话",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { vm.newTopic() }) {
                    Icon(Icons.Outlined.Add, "新建对话", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    val isUser = msg.role == ChatRole.User
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.Bottom  // 头像与气泡底部对齐（TRAE 风格，头像贴底）
                    ) {
                        // 🔵 TRAE 风格固定两列：头像列(40.dp) + 气泡列(weight, max 320dp)
                        //    用户：Spacer 占左侧 → 气泡(偏右小角) → 8dp → 头像(右)
                        //    AI ：头像(左) → 8dp → 气泡(偏左小角)
                        if (isUser) {
                            Spacer(Modifier.weight(1f, fill = true))   // 撑左侧，让头像+气泡整体靠右
                        } else {
                            // AI 头像（左列）：深青色圆圈 + "AI"字样（不搞图片，省流量省代码）
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "AI",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }

                        // 气泡本体：不同角色不同圆角方向（TRAE风格——靠头像的那一侧圆角偏小）
                        //   加 role 语义的"小徽章"只用颜色/圆角区分，不用文字名字（满足你"上面有个我字，我觉得完全没必要"）
                        val bubbleShape = if (isUser) {
                            RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
                        } else {
                            RoundedCornerShape(topStart = 4.dp,  topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                        }
                        Card(
                            modifier = Modifier.widthIn(max = 300.dp),
                            shape = bubbleShape,
                            colors = CardDefaults.cardColors(
                                containerColor = when (msg.role) {
                                    ChatRole.User      -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    ChatRole.Error     -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                                    ChatRole.System    -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
                                    else               -> MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                                }
                            )
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    text = msg.content.ifBlank { " " },
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp,
                                    color = when (msg.role) {
                                        ChatRole.Error  -> MaterialTheme.colorScheme.error
                                        else            -> MaterialTheme.colorScheme.onSurface
                                    }
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

                        if (isUser) {
                            Spacer(Modifier.width(8.dp))
                            // 用户头像（右列）：灰色圆圈 + "我"
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "我",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Spacer(Modifier.weight(0.4f, fill = true))  // AI 侧气泡不贴到最右
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

    // 重命名对话框
    renamingTopic?.let { (id, oldTitle) ->
        var newTitle by remember(id) { mutableStateOf(oldTitle) }
        AlertDialog(
            onDismissRequest = { renamingTopic = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameTopic(id, newTitle)
                    renamingTopic = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renamingTopic = null }) { Text("取消") }
            }
        )
    }

    // 删除确认对话框
    deletingTopic?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { deletingTopic = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「$title」？该对话所有消息将一并删除且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTopic(id)
                    deletingTopic = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTopic = null }) { Text("取消") }
            }
        )
    }
}

/** 简单时间格式化：今天/昨天/日期 + HH:mm */
private fun formatTime(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
    return sdf.format(java.util.Date(ms))
}

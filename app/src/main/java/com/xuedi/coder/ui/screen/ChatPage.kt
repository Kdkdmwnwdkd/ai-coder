package com.xuedi.coder.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.launch
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.vm.ChatViewModel
import com.xuedi.coder.vm.InfStatus

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
    val topics by vm.topics.collectAsStateWithLifecycle()
    val currentTopicId by vm.currentTopicId.collectAsStateWithLifecycle()
    // 🔴 TODO-2 状态条 4 个流：推理状态 / 已生成字数 / 已用秒数 / 失败原因
    val infStatus by vm.inferenceStatus.collectAsStateWithLifecycle()
    val tokenCount by vm.currentTokenCount.collectAsStateWithLifecycle()
    val elapsedSec by vm.inferenceElapsedSec.collectAsStateWithLifecycle()
    val failMsg by vm.failMsgFlow.collectAsStateWithLifecycle()
    val prefillPercent by vm.prefillPercent.collectAsStateWithLifecycle()
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

    // code99: 彻底根治 "entered drag with non-zero pending scroll" 闪退。
    //   根因：scrollToItem(0) 会在 LazyListState 里留下一个非零 pending scroll，
    //         若用户在此 pending scroll 被消费前开始拖拽 → onScroll 抛 IllegalStateException。
    //         之前用 isScrollInProgress / firstVisibleItemIndex 守卫只能缩小时间窗，
    //         无法消除"检查通过→用户开始拖→scrollToItem 执行"的竞态。
    //   根治：LazyColumn 已设 reverseLayout=true，新消息（index 0）天然贴底且可见，
    //         完全不需要程序化 scrollToItem。移除后 pending scroll 永远为 0，闪退不可能发生。
    //         （用户若已上滑浏览历史，新消息到达时不强行拽回底部——这也是主流聊天 App 的标准行为。）

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
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
            // code299: 极简顶栏——按用户红圈标注删掉中间标题（logo/应用名/话题名整块），
            //   只留左右两个图标按钮：左=话题列表抽屉，右=新对话。消息区占满更干净。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Outlined.Menu, "话题列表", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.newTopic() }) {
                    Icon(Icons.Outlined.Add, "新建对话", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // 消息列表（reverseLayout=true + 倒序 items：新消息自然贴底，减少程序化滚动）
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                reverseLayout = true
            ) {
                // code99: key 用 "idx_id" 而非单纯 it.id，彻底杜绝 LazyColumn duplicate key 闪退。
                //   之前：key = { it.id }。正常消息 id 是 UUID 不会撞，但旧版 DB 残留 / 极端情况下
                //         两条消息 id 相同（如 LLM 重复输出同一 action 标签被当 id）→ IllegalArgumentException。
                //   现在：拼上 reversed index，即便 id 撞了，key 也唯一。itemsIndexed 的 index 是
                //         倒序列表的下标（0=最新），稳定且唯一。
                itemsIndexed(messages.asReversed(), key = { idx, msg -> "${idx}_${msg.id}" }) { _, msg ->
                    val isUser = msg.role == ChatRole.User
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.Bottom  // 多行气泡底部对齐（TRAE 风格）
                    ) {
                        // 🔵 TODO-1 TRAE 极简：删掉所有圆形头像 Box / 名字文字 / 左右占位 Spacer
                        //    用户气泡：右对齐淡蓝灰；AI 气泡：左对齐白；统一 18dp 圆角，纯极简
                        val bubbleShape = RoundedCornerShape(18.dp)
                        if (isUser) Spacer(Modifier.weight(1f))  // 用户气泡靠右
                        Card(
                            modifier = Modifier.widthIn(max = 320.dp),
                            shape = bubbleShape,
                            colors = CardDefaults.cardColors(
                                // 白色 DeepSeek 排版：气泡改半透明磨砂容器（非实心蓝），背景照片可透出；
                                // 用户=淡蓝灰 primaryContainer、AI=白 surface、错误/系统=低透明度色调
                                containerColor = when (msg.role) {
                                    ChatRole.User      -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.80f)
                                    ChatRole.Error     -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                    ChatRole.System    -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)
                                    else               -> MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
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
                                        ChatRole.User   -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else            -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                // code277: 动作模式已删除，不再有动作按钮行
                            }
                        }
                        if (!isUser) Spacer(Modifier.weight(1f))  // AI 气泡靠左，不贴右
                    }
                }
            }

            // 🔴 TODO-2 推理状态条（Idle 隐藏 / Preparing 准备 / Running 回复中 / Failed 失败 / Timeout 超时）
            //   取代旧「AI 正在思考并打字…」气泡，用户能看到已用秒数 + 已生成字数 + ✕ 取消
            if (infStatus != InfStatus.Idle) {
                val (bg, fg, statusText) = when (infStatus) {
                    InfStatus.Preparing -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        if (prefillPercent > 0) "正在准备推理…$prefillPercent% · ${formatElapsed(elapsedSec)}"
                        else "正在准备推理…${formatElapsed(elapsedSec)}"
                    )
                    InfStatus.Running -> Triple(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.primary,
                        "AI 正在回复…${formatElapsed(elapsedSec)} · 已生成 $tokenCount 字"
                    )
                    InfStatus.Failed -> Triple(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.error,
                        "推理失败：${(failMsg ?: "未知错误").take(80)}"
                    )
                    InfStatus.Timeout -> Triple(
                        MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.error,
                        "启动超时(15s)：建议减少场景开关数量或重启手机释放内存后重试"
                    )
                    InfStatus.Idle -> Triple(Color.Transparent, Color.Transparent, "")
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = bg)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (infStatus == InfStatus.Preparing || infStatus == InfStatus.Running) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                    color = fg
                                )
                            }
                            Text(
                                statusText,
                                color = fg,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { vm.cancelInference() }) {
                            Icon(
                                Icons.Outlined.Close, "取消",
                                tint = fg,
                                modifier = Modifier.size(18.dp)
                            )
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
                // 白色 DeepSeek 排版：磨砂半透明白输入框（无边框胶囊），背景照片可透出
                androidx.compose.material3.TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            "输入你的问题…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                // 圆形发送按钮（正式 IM 应用风格，空输入置灰；跟随主色灰蓝，不抢戏）
                androidx.compose.material3.FilledIconButton(
                    onClick = {
                        val txt = input
                        if (txt.isNotBlank()) {
                            input = ""
                            vm.sendMessage(txt)
                        }
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(44.dp),
                    shape = androidx.compose.foundation.shape.CircleShape
                ) {
                    Icon(Icons.Outlined.Send, "发送", modifier = Modifier.size(20.dp))
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

/** 推理已耗时格式化：m:ss（00:12） */
private fun formatElapsed(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return String.format("%02d:%02d", m, s)
}

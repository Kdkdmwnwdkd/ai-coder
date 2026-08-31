package com.xuedi.coder.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xuedi.coder.App
import com.xuedi.coder.data.ActionTag
import com.xuedi.coder.data.ChatMsg
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.data.CodeBlock
import com.xuedi.coder.vm.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatPage() {
    val app = LocalContext.current.applicationContext as App
    val vm: ChatViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.Factory { modelClass ->
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                ChatViewModel(app) as androidx.lifecycle.ViewModel
            } else error("Unknown ViewModel: ${modelClass.name}")
        }
    )
    val messages by vm.messages.collectAsState()
    val sending by vm.sending.collectAsState()
    var input by remember { mutableStateOf("") }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current

    // 新消息自动滚到底
    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) {
            scope.launch { kotlinx.coroutines.delay(80) }
            runCatching { scroll.animateScrollToItem(messages.size - 1) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = scroll,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, onCopyCode = { code ->
                    copyText(ctx, code, "代码已复制到剪贴板")
                }, onRunAction = { act ->
                    runActionTag(ctx, act)
                })
            }
            // 流式 pending 时末尾给个小光标
            if (sending) {
                item {
                    Box(
                        Modifier
                            .padding(start = 54.dp, bottom = 6.dp)
                            .size(8.dp, 14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(
                onClick = { vm.clearAll() },
                enabled = !sending,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Icon(Icons.Outlined.DeleteSweep, "清空会话", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("问代码 / 粘贴报错 / 说需求…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp, max = 160.dp),
                minLines = 1,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (sending) {
                        // 发送中点击 = 停止当前生成
                        vm.clearAll()
                    } else {
                        val txt = input.trim()
                        if (txt.isNotBlank()) {
                            input = ""
                            keyboard?.hide()
                            vm.send(txt)
                        }
                    }
                },
                modifier = Modifier
                    .heightIn(min = 54.dp)
                    .minimumInteractiveComponentSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (sending) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                )
            ) {
                AnimatedVisibility(
                    visible = sending,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, Modifier.size(16.dp))
                }
                AnimatedVisibility(
                    visible = !sending,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    Icon(Icons.Outlined.Send, contentDescription = null, Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (sending) "停止" else "发送")
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMsg,
    onCopyCode: (String) -> Unit,
    onRunAction: (ActionTag) -> Unit
) {
    when (msg.role) {
        ChatRole.System -> {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    msg.content,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    lineHeight = 20.sp
                )
            }
        }
        ChatRole.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    msg.content,
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        ChatRole.User -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    modifier = Modifier.padding(end = 4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        msg.content,
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        ChatRole.Assistant -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                // 渲染正文（隐藏 ACTION 标签的原始字符串，只给独立按钮）
                val visibleText = stripActionTags(msg.content)
                Text(
                    visibleText,
                    Modifier.padding(start = 4.dp, end = 30.dp, top = 2.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(6.dp))
                // 代码块
                msg.codeBlocks.forEachIndexed { i, block ->
                    Spacer(Modifier.height(4.dp))
                    CodeBlockView(block, onCopy = { onCopyCode(it) }, index = i)
                    Spacer(Modifier.height(2.dp))
                }
                // ACTION 按钮
                if (msg.actions.isNotEmpty() && !msg.pending) {
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        msg.actions.forEach { a ->
                            ActionButton(a, onClick = { onRunAction(a) })
                        }
                    }
                }
                if (msg.pending) {
                    Text(
                        "正在生成…",
                        Modifier.padding(start = 6.dp, top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: CodeBlock, onCopy: (String) -> Unit, index: Int) {
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val bg = if (MaterialTheme.colorScheme.onBackground == Color.White)
        Color(0xFF232427) else com.xuedi.coder.ui.theme.CodeBlockBg
    val fg = if (MaterialTheme.colorScheme.onBackground == Color.White)
        Color(0xFFE6E7EB) else com.xuedi.coder.ui.theme.CodeBlockText

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column {
            // 顶栏：语言标签 + 复制按钮
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "#${index + 1} ${block.language}",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                    color = fg.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                val ctx = LocalContext.current
                IconButton(onClick = { onCopy(block.code) }) {
                    Icon(Icons.Outlined.ContentCopy, "复制代码", tint = fg.copy(alpha = 0.9f))
                }
            }
            HorizontalDivider(color = borderColor, thickness = 0.5.dp)
            // 代码正文：横向滚动 + 等宽字体
            androidx.compose.foundation.horizontalScroll(
                remember { androidx.compose.foundation.rememberScrollState() }
            ) {
                Text(
                    text = block.code,
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = fg
                )
            }
        }
    }
}

@Composable
private fun ActionButton(action: ActionTag, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "操作: ${action.name}",
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.labelLarge
        )
        if (action.argument.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                "「${action.argument.take(28)}${if (action.argument.length > 28) "…" else ""}」",
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ---------- helpers ----------
private val ACTION_REGEX = Regex("<ACTION:[\\s\\S]*?>")
private fun stripActionTags(text: String): String = ACTION_REGEX.replace(text, "").trimEnd()

private fun copyText(ctx: Context, text: String, toast: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ai_coder", text))
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }
}

private fun runActionTag(ctx: Context, action: ActionTag) {
    when (action.name.lowercase()) {
        "copy_to_clipboard" -> {
            val content = action.argument.ifBlank {
                Toast.makeText(ctx, "复制内容为空", Toast.LENGTH_SHORT).show(); return
            }
            copyText(ctx, content, "已复制到剪贴板")
        }
        "show_toast" -> Toast.makeText(ctx, action.argument.ifBlank { "提示" }, Toast.LENGTH_SHORT).show()
        "vibrate_once" -> {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else @Suppress("DEPRECATION") { v.vibrate(40) }
        }
        else -> {
            // open_app / open_browser / take_screenshot / set_brightness_* 留到 M7
            Toast.makeText(ctx, "动作「${action.name}」将在 V3 版本支持", Toast.LENGTH_SHORT).show()
        }
    }
}

// 避免 import 未被识别的 warning（用于 rememberScrollState）
@Suppress("unused")
private val __scroll_import = androidx.compose.foundation.rememberScrollState

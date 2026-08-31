package com.xuedi.coder.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuedi.coder.data.ChatMsg
import com.xuedi.coder.data.ChatRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 【新 M3 UI 层】聊天 ViewModel（纯 UI 模拟，不接管理层任何东西）。
 * 匹配 data/ChatMsg.kt（M2 已通过的版本）：
 *   · 枚举：ChatRole.User / Assistant / Error / System
 *   · 字段：id / role / content / createdAtMs / codeBlocks / actions / pending
 */
class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow(
        listOf(
            ChatMsg(
                id = "welcome",
                role = ChatRole.Assistant,
                content = "你好！我是 AI 编程助手 🤖\n\n这是 M3 UI 演示版。我现在用的是" +
                    "Mock 流式响应（还没接真 llama.cpp 模型推理）。\n\n你可以先体验：聊天界面、底部4个 Tab、" +
                    "场景页的插件开关、设置页的透明度滑块。\n等 M4=管理层（顺序对调后）再接上" +
                    "真实的 GGUF 模型推理 + 插件 + 背景照片持久化。",
                createdAtMs = System.currentTimeMillis() - 60_000
            )
        )
    )
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    fun sendMessage(text: String) {
        val contentTrimmed = text.trim()
        if (contentTrimmed.isEmpty()) return

        val userMsg = ChatMsg(
            id = "u_${System.currentTimeMillis()}",
            role = ChatRole.User,
            content = contentTrimmed,
            createdAtMs = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            _isTyping.value = true
            delay(400)
            val answerId = "a_${System.currentTimeMillis()}"
            val canned = cannedReply(contentTrimmed)
            _messages.value = _messages.value + ChatMsg(
                id = answerId, role = ChatRole.Assistant, content = "",
                createdAtMs = System.currentTimeMillis(), pending = true
            )
            // 逐字符"流式输出"
            for (i in canned.indices) {
                delay(Random.nextLong(15, 38))
                val newContent = canned.substring(0, i + 1)
                val isLast = i == canned.length - 1
                _messages.value = _messages.value.map { m ->
                    if (m.id == answerId) m.copy(
                        content = newContent,
                        pending = !isLast
                    ) else m
                }
            }
            _isTyping.value = false
        }
    }

    private fun cannedReply(user: String): String {
        val u = user.lowercase()
        return when {
            u.contains("你好") or u.contains("hi") or u.contains("hello") ->
                "你好呀 👋 有什么代码想让我帮你写？\n\n（目前还是 M3 UI 演示版，真正的本地 GGUF 推理会在 M4 接回来。体验一下界面即可。）"

            u.contains("android") or u.contains("安卓") ->
                "好的，Android 场景我可以帮你写：\n" +
                    "  · Jetpack Compose 页面\n" +
                    "  · Room 数据库 / DAO\n" +
                    "  · Coroutines + Flow 异步\n" +
                    "  · Activity / Service / FileProvider 配置\n\n" +
                    "（等管理层接回来后，这里会自动注入 Android 场景的 System Prompt + 正则工具。）"

            u.contains("模型") or u.contains("导入") or u.contains("gguf") ->
                "导入 GGUF 模型 → 请点【设置】Tab，那里有「选择 GGUF 模型」按钮。" +
                    "（真正的 SAF 导入 + 私有目录复制 + JNI 推理启动，是 M4 管理层的职责哦。）"

            u.contains("背景") or u.contains("照片") or u.contains("透明") ->
                "换背景 / 调透明度 → 请点【设置】Tab。M3 UI 版已经把「透明度滑块 + 选择照片入口」" +
                    "都放好了，真正把照片 URI 存起来的 ThemeStore DataStore 持久化会在 M4 接入。"

            else ->
                "我先基于 M3 UI 版给你一个 Mock 回答（真正本地 llama.cpp 推理会在 M4 接回）：\n\n" +
                    "→ 你说的是：「$user」\n\n" +
                    "如果是代码问题，建议你先去【场景】Tab 打开对应场景的开关（Android / Java / " +
                    "Python / Shell），这样下次发消息时，我会带上对应的 System Prompt + 正则工具。" +
                    "\n\n(PS：管理层接回来后，就能解析 ACTION 标签、复制代码、打开 APP 了。)"
        }
    }
}


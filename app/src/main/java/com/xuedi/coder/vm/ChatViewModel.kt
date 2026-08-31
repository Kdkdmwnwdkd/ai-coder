package com.xuedi.coder.vm

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xuedi.coder.App
import com.xuedi.coder.data.ActionTag
import com.xuedi.coder.data.ChatMsg
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.data.CodeBlock
import com.xuedi.coder.model.ChatChunk
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(app: App) : AndroidViewModel(app) {

    private val app = app
    private val _messages = MutableStateFlow<List<ChatMsg>>(
        listOf(
            ChatMsg(
                role = ChatRole.System,
                content = "欢迎使用 AI编程助手。所有推理都在你手机本地进行：\n• 在「场景」页选择编程领域（Android/Java/Python/Shell）\n• 在「设置」页导入 GGUF 模型（推荐 Qwen2.5-Coder-3B-Instruct-Q4_K_M）\n• 返回本页开始聊天即可，代码块右上角有复制按钮。"
            )
        )
    )
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var flowJob: Job? = null

    fun send(text: String) {
        val t = text.trim()
        if (t.isBlank() || _sending.value) return
        flowJob?.cancel()
        // 用户消息入列
        val userMsg = ChatMsg(role = ChatRole.User, content = t)
        val emptyAssistant = ChatMsg(role = ChatRole.Assistant, content = "", pending = true)
        _messages.value = _messages.value + userMsg + emptyAssistant
        _sending.value = true

        flowJob = viewModelScope.launch {
            val systemPrompt = runCatching { app.pluginManager.buildMergedSystemPrompt() }
                .getOrDefault(PluginManager_BASE_PROMPT)
            var full = ""
            try {
                app.llmEngine.chatFlow(systemPrompt, t).collect { chunk ->
                    when (chunk) {
                        is ChatChunk.Token -> {
                            full += chunk.text
                            updateAssistant(emptyAssistant.id) {
                                content = full
                            }
                        }
                        is ChatChunk.Done -> {
                            updateAssistant(emptyAssistant.id) {
                                content = chunk.full
                                pending = false
                                codeBlocks = extractCodeBlocks(chunk.full)
                                actions = extractActions(chunk.full)
                            }
                            _sending.value = false
                        }
                        is ChatChunk.Error -> {
                            updateAssistant(emptyAssistant.id) {
                                if (full.isNotBlank()) content = full
                                pending = false
                            }
                            _messages.value = _messages.value + ChatMsg(
                                role = ChatRole.Error,
                                content = chunk.hint
                            )
                            _sending.value = false
                        }
                    }
                }
            } catch (t: Throwable) {
                _messages.value = _messages.value + ChatMsg(
                    role = ChatRole.Error,
                    content = "推理中断：${t.message ?: t.javaClass.simpleName}"
                )
                _sending.value = false
            }
        }
    }

    fun clearAll() {
        flowJob?.cancel()
        _sending.value = false
        _messages.value = _messages.value.take(1)
    }

    private inline fun updateAssistant(id: String, block: ChatMsg.() -> Unit) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.apply(block) else it
        }
    }

    companion object {
        // 解析 ```lang\n...\n```
        private val CODE_BLOCK = Regex("```([A-Za-z0-9_+-]*)\\s*\\n([\\s\\S]*?)```", setOf(RegexOption.MULTILINE))
        fun extractCodeBlocks(text: String): List<CodeBlock> {
            val blocks = mutableListOf<CodeBlock>()
            CODE_BLOCK.findAll(text).forEachIndexed { i, mr ->
                val lang = mr.groupValues[1].ifBlank { "text" }
                val code = mr.groupValues[2].removeSuffix("\n")
                blocks += CodeBlock(index = i, language = lang, code = code)
            }
            return blocks
        }

        // 解析 <ACTION: name "arg">  —— 白名单名在 PluginManager 里，这里只抽
        private val ACTION_TAG = Regex("<ACTION:\\s*([A-Za-z0-9_\\-]+)\\s+\"([^\"]*)\"\\s*>", setOf(RegexOption.DOT_MATCHES_ALL))
        fun extractActions(text: String): List<ActionTag> {
            return ACTION_TAG.findAll(text).map { mr ->
                ActionTag(name = mr.groupValues[1].trim(), argument = mr.groupValues[2], raw = mr.value)
            }.toList()
        }

        private const val PluginManager_BASE_PROMPT = "你是本地运行的 AI编程助手。默认给出可复制的完整代码，代码块用 ```语言``` 包裹。"
    }
}

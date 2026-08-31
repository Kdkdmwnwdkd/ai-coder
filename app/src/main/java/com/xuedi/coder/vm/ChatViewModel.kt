package com.xuedi.coder.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuedi.coder.App
import com.xuedi.coder.action.ActionExecutor
import com.xuedi.coder.data.ChatMsg
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.model.ChatChunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 【新 M4 = 管理层】聊天 ViewModel —— 真正接上管理层三件套 + LlmEngine 接口。
 *
 * 结构：
 *   sendMessage(text)
 *      └─ viewModelScope.launch
 *           1. append 用户消息到 _messages
 *           2. pluginManager.buildMergedSystemPrompt() ← 合并所有启用场景的 system prompt
 *           3. llmEngine.chatFlow(system, user) 流式收集
 *                  ├─ ChatChunk.Token(t)  → 实时拼接到 assistant 消息
 *                  └─ ChatChunk.Done(full) → 1) ActionExecutor.extractActions(full)
 *                                            2) msg.actions = 解析出的 ActionTag 列表
 *                                            3) ActionExecutor.executeAll（应用操作）
 *                                            4) msg.content = 清理标签后的正文
 *                  （ChatChunk.Error 单独一条 ChatRole.Error 消息）
 *
 * 构造函数保持零参（与 M3 一致）— viewModel() 默认工厂能直接实例化。
 * 单例通过 App.instance.* lazy 获取（lazy 线程安全 + 首次访问才创建）。
 */
class ChatViewModel : ViewModel() {

    private val app get() = App.instance

    private val _messages = MutableStateFlow(
        listOf(
            ChatMsg(
                id = "welcome",
                role = ChatRole.Assistant,
                content = "你好！我是 AI 编程助手 🤖\n\n现在是 **新 M4=管理层** 版本（UI层已经对调顺序提前做好，管理层现在接回来了）。\n\n已经接回来的能力：\n  · 场景/插件：Android / Java / Python / Shell 四个场景，打开后会注入对应 System Prompt\n  · 背景照片 + 透明度 持久化（DataStore，关掉APP重开也在）\n  · GGUF 模型导入入口（Settings→选GGUF文件→写入Room。JNI推理 M5 再上）\n  · ACTION 标签解析执行（复制代码/打开APP/跳转设置/震动等）\n\n想测试的话：\n  - 试试发「写个安卓按钮」→ 会注入 Android 场景 system prompt，Mock流式输出代码+最后带 <ACTION: copy_to_clipboard ...>，你会收到系统剪贴板 Toast\n  - 到【场景】Tab开/关几个场景，返回聊天页发消息，就能感知 system prompt 变化\n  - 到【设置】Tab调透明度滑块、选照片，重开APP仍保留",
                createdAtMs = System.currentTimeMillis() - 60_000
            )
        )
    )
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return

        val userMsg = ChatMsg(
            id = "u_${System.currentTimeMillis()}",
            role = ChatRole.User,
            content = content,
            createdAtMs = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            _isTyping.value = true
            val answerId = "a_${System.currentTimeMillis()}"
            val system = runCatching { app.pluginManager.buildMergedSystemPrompt() }
                .getOrDefault(PluginManagerFallback.BASE_PROMPT)

            // 先插一条空的 assistant 消息（pending=true）
            _messages.value = _messages.value + ChatMsg(
                id = answerId,
                role = ChatRole.Assistant,
                content = "",
                createdAtMs = System.currentTimeMillis(),
                pending = true
            )

            var sb = StringBuilder()
            runCatching {
                app.llmEngine.chatFlow(system, content).collectLatest { chunk ->
                    when (chunk) {
                        is ChatChunk.Token -> {
                            sb.append(chunk.text)
                            _messages.value = _messages.value.map { m ->
                                if (m.id == answerId) m.copy(content = sb.toString()) else m
                            }
                        }
                        is ChatChunk.Done -> {
                            // 引擎 emit 完 Done 时，用 full 做最终一致性拼接
                            sb = StringBuilder(chunk.full)
                            // 解析 ACTION 标签
                            val (cleaned, actions) = ActionExecutor.extractActions(chunk.full)
                            // 执行 ACTION（复制/打开APP/震动/Toast 等）
                            runCatching {
                                ActionExecutor.executeAll(App.instance, actions)
                            }
                            _messages.value = _messages.value.map { m ->
                                if (m.id == answerId) m.copy(
                                    content = cleaned.ifBlank { chunk.full },
                                    actions = actions,
                                    pending = false
                                ) else m
                            }
                        }
                        is ChatChunk.Error -> {
                            // 引擎报错 → 在当前 assistant 消息后追加一条 Error 消息
                            _messages.value = _messages.value
                                .map { m -> if (m.id == answerId) m.copy(pending = false) else m } +
                                ChatMsg(
                                    id = "err_${System.currentTimeMillis()}",
                                    role = ChatRole.Error,
                                    content = "❌ ${chunk.hint}",
                                    createdAtMs = System.currentTimeMillis()
                                )
                        }
                    }
                }
            }.onFailure { t ->
                // 引擎本身抛异常 → Error 消息兜底
                _messages.value = _messages.value
                    .map { m -> if (m.id == answerId) m.copy(pending = false) else m } +
                    ChatMsg(
                        id = "err_${System.currentTimeMillis()}",
                        role = ChatRole.Error,
                        content = "❌ 推理崩溃：${t.message ?: t.javaClass.simpleName}",
                        createdAtMs = System.currentTimeMillis()
                    )
            }
            _isTyping.value = false
        }
    }

    // 如果 PluginManager 还没初始化完（race），用这个兜底 BASE_PROMPT，不会 sendMessage
    private object PluginManagerFallback {
        const val BASE_PROMPT = "你是运行在用户手机本地的 AI编程助手。请用简体中文回答。"
    }
}

package com.xuedi.coder.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuedi.coder.App
import com.xuedi.coder.action.ActionExecutor
import com.xuedi.coder.data.ChatDatabase
import com.xuedi.coder.data.ChatMsg
import com.xuedi.coder.data.ChatMsgEntity
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.data.ChatTopicEntity
import com.xuedi.coder.model.ChatChunk
import com.xuedi.coder.model.InferenceForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 【M8 多话题】ChatViewModel —— 类 ChatGPT 侧边栏多会话。
 *
 * 数据模型：
 *   - ChatTopicEntity（chat_topic 表）：id/title/createdAtMs/lastActiveMs
 *   - ChatMsgEntity（chat_message 表）：加 topicId 外键字段
 *
 * 状态：
 *   - topics: StateFlow<List<ChatTopicEntity>>：侧边栏话题列表，按 lastActiveMs DESC
 *   - currentTopicId: StateFlow<String?>：当前选中的话题 id（null=还没话题，UI 显示空状态）
 *   - messages: StateFlow<List<ChatMsg>>：当前话题的消息列表
 *
 * 操作：
 *   - newTopic(firstUserMsg): 创建新话题（首条用户消息作默认标题）
 *   - switchTopic(id): 切换当前话题，加载该 topic 的消息
 *   - renameTopic(id, title): 重命名
 *   - deleteTopic(id): 删除话题 + 级联删该 topic 所有消息
 *
 * 持久化策略：
 *   - send 消息时如果当前无 topic，自动 newTopic
 *   - 每条消息 send / streaming 中每 64 字节 / Done / Error 时 upsert
 *   - Done 后 touchActive(topicId, now)
 */
class ChatViewModel : ViewModel() {

    private val app get() = App.instance
    private val db by lazy { ChatDatabase.get(app) }
    private val chatDao by lazy { db.dao() }
    private val topicDao by lazy { db.topicDao() }

    // ---------- StateFlows ----------
    private val _topics = MutableStateFlow<List<ChatTopicEntity>>(emptyList())
    val topics: StateFlow<List<ChatTopicEntity>> = _topics.asStateFlow()

    private val _currentTopicId = MutableStateFlow<String?>(null)
    val currentTopicId: StateFlow<String?> = _currentTopicId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    // ---------- init: 订阅 topics 列表 + 自动选最近一个话题 ----------
    init {
        viewModelScope.launch(Dispatchers.IO) {
            topicDao.observeAll().collectLatest { list ->
                _topics.value = list
                // 启动时如果没选 topic，自动选最近活跃的那个
                if (_currentTopicId.value == null && list.isNotEmpty()) {
                    switchTopicInternal(list.first().id)
                }
                // 当前 topic 被删了 → 清空 messages，UI 显示空状态
                if (_currentTopicId.value != null && list.none { it.id == _currentTopicId.value }) {
                    _currentTopicId.value = null
                    _messages.value = emptyList()
                }
            }
        }
    }

    // ---------- 话题操作 ----------

    /** 创建新话题，返回 topicId；可选首条用户消息用于自动生成标题 */
    private suspend fun createTopic(firstUserMsg: String? = null): String {
        val now = System.currentTimeMillis()
        val id = "t_${now}_${UUID.randomUUID().toString().take(8)}"
        val title = firstUserMsg
            ?.takeIf { it.isNotBlank() }
            ?.let { it.trim().take(24) + if (it.trim().length > 24) "…" else "" }
            ?: "新对话 ${now % 100000}"
        topicDao.upsert(ChatTopicEntity(
            id = id, title = title, createdAtMs = now, lastActiveMs = now
        ))
        _currentTopicId.value = id
        _messages.value = emptyList()
        return id
    }

    /** 切换当前话题 → 重新加载该 topic 的消息 */
    fun switchTopic(topicId: String) {
        if (_currentTopicId.value == topicId) return
        viewModelScope.launch(Dispatchers.IO) {
            // 先取消当前正在跑的推理（避免旧 topic 的 token 流到新 topic）
            runCatching { app.llmEngine.cancel() }
            runCatching { InferenceForegroundService.stop(app) }
            _isTyping.value = false
            switchTopicInternal(topicId)
        }
    }

    private suspend fun switchTopicInternal(topicId: String) {
        _currentTopicId.value = topicId
        val msgs = runCatching { chatDao.getByTopic(topicId) }.getOrDefault(emptyList())
        _messages.value = msgs.map { e ->
            ChatMsgEntity.toMsg(e).copy(pending = false)  // 强制清 pending（上次杀进程可能残留）
        }
    }

    /** 新建话题：UI 点「+ 新对话」按钮 → 立即创建空 topic + 切过去 */
    fun newTopic() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.llmEngine.cancel() }
            runCatching { InferenceForegroundService.stop(app) }
            _isTyping.value = false
            createTopic(firstUserMsg = null)
        }
    }

    /** 重命名话题 */
    fun renameTopic(topicId: String, title: String) {
        val t = title.trim().take(60)
        if (t.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            topicDao.rename(topicId, t)
        }
    }

    /** 删除话题 + 级联删该 topic 所有消息 */
    fun deleteTopic(topicId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 如果删的是当前话题，先取消推理
            if (_currentTopicId.value == topicId) {
                runCatching { app.llmEngine.cancel() }
                runCatching { InferenceForegroundService.stop(app) }
                _isTyping.value = false
            }
            chatDao.deleteByTopic(topicId)
            topicDao.deleteById(topicId)
            // 删完后如果没话题了，自动建一个新空 topic 让 UI 不至于显示"无话题"
            val remain = topicDao.getAll()
            if (remain.isEmpty()) {
                createTopic(firstUserMsg = null)
            } else if (_currentTopicId.value == topicId) {
                // 当前话题被删 → 切到最近活跃的
                switchTopicInternal(remain.first().id)
            }
        }
    }

    // ---------- 发送消息 ----------

    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return

        viewModelScope.launch {
            // 当前没话题 → 用首条用户消息自动创建一个
            val topicId = _currentTopicId.value ?: createTopic(firstUserMsg = content)

            val userMsg = ChatMsg(
                id = "u_${System.currentTimeMillis()}",
                role = ChatRole.User,
                content = content,
                createdAtMs = System.currentTimeMillis()
            )
            _messages.value = _messages.value + userMsg
            viewModelScope.launch(Dispatchers.IO) {
                chatDao.upsert(ChatMsgEntity.from(userMsg, topicId))
                topicDao.touchActive(topicId, System.currentTimeMillis())
            }

            _isTyping.value = true
            val answerId = "a_${System.currentTimeMillis()}"
            val system = runCatching { app.pluginManager.buildMergedSystemPrompt() }
                .getOrDefault(BASE_PROMPT)

            runCatching { InferenceForegroundService.start(app) }

            val answer = ChatMsg(
                id = answerId,
                role = ChatRole.Assistant,
                content = "",
                createdAtMs = System.currentTimeMillis(),
                pending = true
            )
            _messages.value = _messages.value + answer
            viewModelScope.launch(Dispatchers.IO) {
                chatDao.upsert(ChatMsgEntity.from(answer, topicId))
            }

            var sb = StringBuilder()
            runCatching {
                app.llmEngine.chatFlow(system, content).collectLatest { chunk ->
                    when (chunk) {
                        is ChatChunk.Token -> {
                            sb.append(chunk.text)
                            _messages.value = _messages.value.map { m ->
                                if (m.id == answerId) m.copy(content = sb.toString()) else m
                            }
                            if (sb.length and 0x3F == 0) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    val cur = _messages.value.first { it.id == answerId }
                                    chatDao.upsert(ChatMsgEntity.from(cur, topicId))
                                }
                            }
                        }
                        is ChatChunk.Done -> {
                            sb = StringBuilder(chunk.full)
                            val (cleaned, actions) = ActionExecutor.extractActions(chunk.full)
                            val finalText = cleaned.ifBlank { chunk.full }
                            val finalMsg = ChatMsg(
                                id = answerId,
                                role = ChatRole.Assistant,
                                content = finalText,
                                createdAtMs = answer.createdAtMs,
                                actions = actions,
                                pending = false
                            )
                            _messages.value = _messages.value.map { m ->
                                if (m.id == answerId) finalMsg else m
                            }
                            viewModelScope.launch(Dispatchers.IO) {
                                chatDao.upsert(ChatMsgEntity.from(finalMsg, topicId))
                                topicDao.touchActive(topicId, System.currentTimeMillis())
                            }
                        }
                        is ChatChunk.Error -> {
                            _messages.value = _messages.value
                                .map { m -> if (m.id == answerId) m.copy(pending = false) else m } +
                                ChatMsg(
                                    id = "err_${System.currentTimeMillis()}",
                                    role = ChatRole.Error,
                                    content = "❌ ${chunk.hint}",
                                    createdAtMs = System.currentTimeMillis()
                                )
                            viewModelScope.launch(Dispatchers.IO) {
                                val cur = _messages.value.first { it.id == answerId }
                                chatDao.upsert(ChatMsgEntity.from(cur.copy(pending = false), topicId))
                            }
                        }
                    }
                }
            }.onFailure { t ->
                _messages.value = _messages.value
                    .map { m -> if (m.id == answerId) m.copy(pending = false) else m } +
                    ChatMsg(
                        id = "err_${System.currentTimeMillis()}",
                        role = ChatRole.Error,
                        content = "❌ 推理崩溃：${t.message ?: t.javaClass.simpleName}",
                        createdAtMs = System.currentTimeMillis()
                    )
                viewModelScope.launch(Dispatchers.IO) {
                    val cur = _messages.value.first { it.id == answerId }
                    chatDao.upsert(ChatMsgEntity.from(cur.copy(pending = false), topicId))
                }
            }

            runCatching { InferenceForegroundService.stop(app) }
            _isTyping.value = false
        }
    }

    /** 退出聊天页 / 回桌面时取消推理 + 清 pending + 释放前台服务 */
    fun cancelInference() {
        _isTyping.value = false
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { app.llmEngine.cancel() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { InferenceForegroundService.stop(app) }
        }
        val cur = _messages.value
        if (cur.any { it.pending }) {
            _messages.value = cur.map { if (it.pending) it.copy(pending = false) else it }
            // 当前没话题则跳过持久化（外层 fun 不是 launch，不能用 return@launch）
            val tid = _currentTopicId.value
            if (tid != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    _messages.value.filter { it.id != "welcome" }.forEach {
                        chatDao.upsert(ChatMsgEntity.from(it, tid))
                    }
                }
            }
        }
    }

    private companion object {
        // 通用对话 + 写代码两不误。用户可闲聊，也可问代码。
        // 之所以不是"AI 编程助手"是为了避免 Qwen2.5-Coder 等代码专用模型拒绝闲聊；
        // 通用对话模型（Qwen2.5-Instruct、Phi-3、Yi 等）能在此提示词下既闲聊又写代码。
        const val BASE_PROMPT = "你是一个运行在用户手机本地的 AI 助手。用简体中文回答。" +
            "你可以和用户闲聊、回答知识问题，也可以写代码或解释代码。" +
            "保持回答简洁友好。"
    }
}

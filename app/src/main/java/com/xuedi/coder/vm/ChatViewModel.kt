package com.xuedi.coder.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuedi.coder.App
import com.xuedi.coder.action.ActionExecutor
import com.xuedi.coder.data.ChatDatabase
import com.xuedi.coder.data.ChatMsgEntity
import com.xuedi.coder.data.ChatMsg
import com.xuedi.coder.data.ChatRole
import com.xuedi.coder.model.ChatChunk
import com.xuedi.coder.model.InferenceForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 【修复 4 Bug】：
 *   ① 聊天消息持久化（init 时从 ChatDatabase 加载；所有变动同步 upsert；死要求 welcome 消息内置不入库）
 *   ② 推理结束后清空 pending 标记，避免下次读取还在"打字中"
 *   ③ 开始推理前调 InferenceForegroundService.start(ctx) — 避免 Flyme 后台 3 分钟被杀
 *   ④ 推理 Done / Error / 取消 调 stop 释放前台保活
 */
class ChatViewModel : ViewModel() {

    private val app get() = App.instance
    private val chatDao by lazy { ChatDatabase.get(app).dao() }

    // ---------- 欢迎消息（id=welcome，不入库，每次构造都重新加；入库会造成 welcome createdAtMs 陈旧）----------
    private val welcomeMsg = ChatMsg(
        id = "welcome",
        role = ChatRole.Assistant,
        content = "你好！我是 AI 编程助手 🤖\n\n接入了真 JNI llama.cpp 推理 + ACTION 按钮 + 聊天记录持久化。\n\n" +
            "用法：\n" +
            "  1. 设置 → 导入 GGUF 模型（推荐 Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf）→ 设为当前\n" +
            "  2. 聊天页输入问题 → 气泡**逐字跳出**（真推理），结束后下方出「复制/打开链接」等 ACTION 按钮\n" +
            "  3. 退出 APP / 锁屏再回来，消息列表还在 ✅\n\n" +
            "提示：如提示「进入 fallback 模式」，说明 JNI 没加载成功（或还没设模型），先去设置页导入 GGUF。",
        createdAtMs = System.currentTimeMillis() - 60_000
    )

    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()

    private val _isTyping = MutableStateFlow(false)
    val isTyping: StateFlow<Boolean> = _isTyping.asStateFlow()

    // ---------- 1. 从 ChatDatabase 恢复持久化消息（异步，不阻塞 UI）----------
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = runCatching { chatDao.getAll() }.getOrDefault(emptyList())
            // ChatMsgEntity -> ChatMsg：强制设 pending=false（上次杀进程可能是 pending=true）
            val recovered = persisted
                .filter { it.id != "welcome" }
                .map { e ->
                    val m = ChatMsgEntity.toMsg(e)
                    m.copy(pending = false)
                }
            _messages.value = listOf(welcomeMsg) + recovered
        }
    }

    // ---------- 2. 所有变动同步保存 ----------
    private suspend fun save(m: ChatMsg) {
        if (m.id == "welcome") return  // welcome 不存
        runCatching { chatDao.upsert(ChatMsgEntity.from(m)) }
    }
    private suspend fun saveAll(list: List<ChatMsg>) {
        val toStore = list.filter { it.id != "welcome" }.map { ChatMsgEntity.from(it) }
        if (toStore.isEmpty()) return
        runCatching { chatDao.upsertAll(toStore) }
    }

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
        viewModelScope.launch(Dispatchers.IO) { save(userMsg) }

        viewModelScope.launch {
            _isTyping.value = true
            val answerId = "a_${System.currentTimeMillis()}"
            val system = runCatching { app.pluginManager.buildMergedSystemPrompt() }
                .getOrDefault(PluginManagerFallback.BASE_PROMPT)

            // ---------- ③ 前台保活：开始推理前启动 ----------
            runCatching { InferenceForegroundService.start(app) }

            // 先插一条空的 assistant 消息（pending=true）
            val answer = ChatMsg(
                id = answerId,
                role = ChatRole.Assistant,
                content = "",
                createdAtMs = System.currentTimeMillis(),
                pending = true
            )
            _messages.value = _messages.value + answer
            viewModelScope.launch(Dispatchers.IO) { save(answer) }

            var sb = StringBuilder()
            runCatching {
                app.llmEngine.chatFlow(system, content).collectLatest { chunk ->
                    when (chunk) {
                        is ChatChunk.Token -> {
                            sb.append(chunk.text)
                            _messages.value = _messages.value.map { m ->
                                if (m.id == answerId) m.copy(content = sb.toString()) else m
                            }
                            // 流式过程中每 50 个 token 持久化一次（避免 OOM 时整段丢失）
                            if (sb.length and 0x3F == 0) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    chatDao.upsert(
                                        ChatMsgEntity.from(
                                            _messages.value.first { it.id == answerId }
                                        )
                                    )
                                }
                            }
                        }
                        is ChatChunk.Done -> {
                            sb = StringBuilder(chunk.full)
                            val (cleaned, actions) = ActionExecutor.extractActions(chunk.full)
                            runCatching { ActionExecutor.executeAll(App.instance, actions) }
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
                            viewModelScope.launch(Dispatchers.IO) { save(finalMsg) }
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
                                saveAll(_messages.value)
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
                viewModelScope.launch(Dispatchers.IO) { saveAll(_messages.value) }
            }

            // ---------- ④ 前台保活：结束/取消后释放 ----------
            runCatching { InferenceForegroundService.stop(app) }
            _isTyping.value = false
        }
    }

    // 如果 PluginManager 还没初始化完（race），用这个兜底 BASE_PROMPT
    private object PluginManagerFallback {
        const val BASE_PROMPT = "你是运行在用户手机本地的 AI编程助手。请用简体中文回答。"
    }

    /**
     * 【Step 4/5 关键：退出聊天页 / 回桌面 时取消推理 + 释放前台服务】
     * - 调 LlmEngine.cancel()（底层 LlamaJniEngine.nativeChatCancel → C++ cancel=true → while 循环跳出）
     * - 把所有 pending 消息的 pending 位清掉（避免下次进来看见"打字中"永久挂在那里）
     * - 立即释放前台保活（START_STICKY 重启的 service 也能被 stopService 关掉）
     */
    fun cancelInference() {
        _isTyping.value = false
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { app.llmEngine.cancel() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { InferenceForegroundService.stop(app) }
        }
        // 同步清 UI 上的 pending 标记
        val cur = _messages.value
        if (cur.any { it.pending }) {
            _messages.value = cur.map { if (it.pending) it.copy(pending = false) else it }
            viewModelScope.launch(Dispatchers.IO) { saveAll(_messages.value) }
        }
    }
}

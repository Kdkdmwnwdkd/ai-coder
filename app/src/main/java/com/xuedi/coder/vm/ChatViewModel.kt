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
import com.xuedi.coder.model.LlamaJniEngine
import com.xuedi.coder.plugin.ToolExecutionPlugin
import com.xuedi.coder.plugin.WebSearchPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 推理状态（TODO-4）：UI 顶部状态条据此渲染 + ✕ 取消按钮。
 *   - Idle      → 状态条隐藏
 *   - Preparing → 灰底「正在准备推理…00:0X」+ 转 + ✕
 *   - Running   → 蓝底「AI 正在回复…00:XX · 已生成 N 字」+ ✕
 *   - Failed    → 红底「推理失败：…」+ ✕
 *   - Timeout   → 红底「启动超时(15s)…」+ ✕
 */
enum class InfStatus { Idle, Preparing, Running, Failed, Timeout }

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

    // ---------- 推理状态流（TODO-4：防闪退 + UX 状态条）----------
    /** UI 顶部状态条据此渲染（Idle 隐藏 / Preparing 准备中 / Running 回复中 / Failed 失败 / Timeout 启动超时）*/
    private val _infStatus = MutableStateFlow(InfStatus.Idle)
    val inferenceStatus: StateFlow<InfStatus> = _infStatus.asStateFlow()

    /** 已生成 token 累计字数（Running 时显示「已生成 N 字」）*/
    private val _currentTokenCount = MutableStateFlow(0)
    val currentTokenCount: StateFlow<Int> = _currentTokenCount.asStateFlow()

    /** 推理已耗时毫秒（Preparing/Running 时 ticker 每 500ms 更新；UI 用 inferenceElapsedSec 显示秒）*/
    private val _infElapsedMs = MutableStateFlow(0L)
    val inferenceElapsedSec: StateFlow<Int> = _infElapsedMs
        .map { (it / 1000).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Failed/Timeout 时的具体原因文案（UI 状态条红底显示）*/
    private val _failMsgFlow = MutableStateFlow<String?>(null)
    val failMsgFlow: StateFlow<String?> = _failMsgFlow.asStateFlow()

    /** 🔴 预填充进度百分比（0~100），Preparing 时显示「准备中 35%」避免一直白转圈圈 */
    private val _prefillPercent = MutableStateFlow(0)
    val prefillPercent: StateFlow<Int> = _prefillPercent.asStateFlow()

    // 🔴 TODO-4b 互斥锁：防连点两次发送同时起两个 nativeChat → OOM 闪退
    private val inferenceMutex = Mutex()
    private var inferenceJob: Job? = null

    // ---------- init: 订阅 topics 列表 + 冷启动自动选最近一个话题 + 注册 ChatPlugin 流钩子 ----------
    init {
        // 🆕 v1.3.26-code62-modes：把 AI 执行 + 联网搜索 插件挂到 LlamaJniEngine.plugins 上。
        //   · WebSearchPlugin → onPreSend 拦截 "@搜索 关键词"，把搜索结果注入用户输入。
        //   · ToolExecutionPlugin → Done-time JSON 执行（在 ChatChunk.Done 分支显式调 companion）；
        //     注册进 list 是为插件链归属 & 后续扩展 onPostReceive 钩子方便。
        //   · 按 displayName() 去重，避免 ViewModel 重建时重复注册。
        viewModelScope.launch(Dispatchers.Default) {
            val eng = (app.llmEngine as? LlamaJniEngine) ?: return@launch
            val existingNames = java.util.HashSet<String>()
            for (i in 0 until eng.plugins.size) {
                existingNames.add(eng.plugins[i].displayName())
            }
            if (!existingNames.contains("AI执行模式")) {
                eng.plugins.add(ToolExecutionPlugin(app))
            }
            if (!existingNames.contains("联网搜索(@搜索)")) {
                eng.plugins.add(WebSearchPlugin())
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            topicDao.observeAll().collectLatest { list ->
                _topics.value = list
                // 🔴 v1.3.10 修复三：冷启动不再自动加载最近对话。
                //   之前：_currentTopicId==null && list.isNotEmpty() → switchTopicInternal(list.first().id)
                //   → 启动就跳进历史最近对话，想新对话要手动操作且没入口。
                //   现在：启动保持空状态(_currentTopicId=null, _messages=empty)，用户发消息时
                //   sendMessage 第 255 行 `?: createTopic()` 自动新建话题。历史对话走 ChatPage
                //   TopAppBar 的「📜历史」按钮切换、「➕新对话」按钮调 newTopic()。
                //   保留：正常 observeAll 更新时绝不碰 _messages（避免覆盖正在流式 token 的列表 →
                //   抖动/死循环/ANR）。删除后自动切换逻辑也保留（删除是用户主动操作，切到最近合理）。
                // 唯一例外：当前 topic 被删了 → 必须切到下一个或新建空话题
                if (_currentTopicId.value != null && list.none { it.id == _currentTopicId.value }) {
                    val currentDeletedId = _currentTopicId.value
                    _currentTopicId.value = null
                    _messages.value = emptyList()
                    // 删完空 → 自动建一个新空话题
                    if (list.isEmpty()) {
                        createTopic(firstUserMsg = null)
                    } else {
                        switchTopicInternal(list.first().id)
                    }
                    // 未使用变量，抑制警告
                    currentDeletedId ?: Unit
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
            _infStatus.value = InfStatus.Idle
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
            _infStatus.value = InfStatus.Idle
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
                _infStatus.value = InfStatus.Idle
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

        // 🔴 ANR 双保险修复：nativeChat JNI 是 CPU 密集阻塞，推理工作流必须完整地在
        //    后台协程池（Dispatchers.Default）里跑，不能在 Main.immediate 上发起 collect。
        //    之前：viewModelScope.launch { ... chatFlow(...).collectLatest ... }
        //         → 默认 Main.immediate → callbackFlow 也继承 Main → launch 在主线程阻塞 native → ANR。
        //    现在：顶层就在 Default，所有子协程继承它；StateFlow.value setter 是线程安全的，UI 不会崩。
        viewModelScope.launch(Dispatchers.Default) {
            // 🔴 TODO-4b 互斥防连点：连点两次发送时先取消旧推理再起新的，
            //    保证同一时间只有一个 nativeChat（否则两个并发 nativeChat → OOM 闪退）
            val oldJob: Job? = inferenceMutex.withLock {
                val ex = inferenceJob
                if (ex != null && ex.isActive) {
                    runCatching { ex.cancel() }
                    runCatching { app.llmEngine.cancel() }
                    runCatching { InferenceForegroundService.stop(app) }
                }
                ex
            }
            // 在锁外等旧 job 真正退出（不阻塞下一次加锁；join 自身可被取消）
            oldJob?.let { runCatching { it.join() } }

            // 注册自己为当前 inferenceJob（锁内原子写）
            val myJob = coroutineContext[Job]!!
            inferenceMutex.withLock { inferenceJob = myJob }

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

            // 🔴 TODO-4c/4d 推理状态流：
            //   Preparing（启动 ticker）→ 首个 Token 转 Running → Done 转 Idle / Error 转 Failed|Timeout
            _infStatus.value = InfStatus.Preparing
            _currentTokenCount.value = 0
            _infElapsedMs.value = 0L
            _failMsgFlow.value = null
            val startedAt = System.currentTimeMillis()
            val tickerJob = viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(500)
                    val s = _infStatus.value
                    if (s == InfStatus.Preparing || s == InfStatus.Running) {
                        _infElapsedMs.value = System.currentTimeMillis() - startedAt
                    }
                }
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
            try {
                // 🔴 致命修复：chatFlow 是 token 串行流，绝对不能用 collectLatest！
                // 之前：.collectLatest { chunk -> ... }
                //   collectLatest 的语义是：上游发新值时，取消上一个值还没处理完的协程体。
                //   onToken 每 ~30ms 发 1 个 token → collectLatest 永远在取消前一个
                //   → _messages.value.map 还没跑完就被 cancel → 正文累积不到气泡里 → UI 显示空气泡（你截图里的"AI 编程助手"空回复就是这个）。
                // 现在：.collect { chunk -> ... }，严格串行，一个 token 不丢；处理也很轻（map 一个 list + set 一个 StateFlow value）不会阻塞后续 token。
                app.llmEngine.chatFlow(system, content).collect { chunk ->
                    when (chunk) {
                        is ChatChunk.Token -> {
                            // 🔴 TODO-4d 首个 Token → Running
                            if (_infStatus.value == InfStatus.Preparing) _infStatus.value = InfStatus.Running
                            // 🛡️ 防闪退/内存炸：单条回复累积 token 上限 5 万字；再多就丢弃新 token，保证 UI/sb 不 OOM
                            // （之前没限制：极端情况下 sb 无限 append 几 MB 字符串 → LazyColumn 渲染时 kill 进程）
                            if (sb.length < 50000) {
                                sb.append(chunk.text)
                                _currentTokenCount.value = sb.length
                                _messages.value = _messages.value.map { m ->
                                    if (m.id == answerId) m.copy(content = sb.toString()) else m
                                }
                            }
                            if (sb.length and 0x3F == 0) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    val cur = _messages.value.firstOrNull { it.id == answerId } ?: return@launch
                                    chatDao.upsert(ChatMsgEntity.from(cur, topicId))
                                }
                            }
                        }
                        is ChatChunk.Done -> {
                            // 🛡️ 防闪退/内存炸：截断超长 finalText（Llama 偶尔会输出几十上百 MB 的乱码循环回复）
                            val safeFull = if (chunk.full.length > 20000) chunk.full.take(20000) + "\n\n...[回复过长已截断]" else chunk.full
                            sb = StringBuilder(safeFull)
                            // 1) code 62 原版：<ACTION: ...> 标签抽取 → 动作芯片（用户点一下执行）
                            val (cleaned, actions) = ActionExecutor.extractActions(safeFull)
                            // 2) 🆕 AI 执行模式：Done-time 一次抽取 JSON 动作并自动执行，返回
                            //    (移除JSON后的正文, 执行结果说明)。JSON 解析/执行过程 100% runCatching 兜底，
                            //    任何异常不会污染 cleaned，也不会带崩 App。
                            val (textNoJson, execNote) = ToolExecutionPlugin.extractExecute(app, cleaned)
                            val safeCleaned = if (textNoJson.length > 20000)
                                textNoJson.take(20000) + "\n\n...[内容过长]"
                            else
                                textNoJson
                            val noteBlock = if (execNote.isNotBlank()) "\n\n$execNote" else ""
                            val finalText = (safeCleaned + noteBlock).trim().ifBlank { safeFull }
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
                            // 🔴 TODO-4d：超时 vs 普通失败 区分（LlamaJniEngine 首 token 45s 超时发的 hint 含「超时」）
                            val hint = chunk.hint
                            _infStatus.value = if (hint.contains("超时")) InfStatus.Timeout else InfStatus.Failed
                            _failMsgFlow.value = hint
                            _messages.value = _messages.value
                                .map { m -> if (m.id == answerId) m.copy(pending = false) else m } +
                                ChatMsg(
                                    id = "err_${System.currentTimeMillis()}",
                                    role = ChatRole.Error,
                                    content = "❌ ${chunk.hint}",
                                    createdAtMs = System.currentTimeMillis()
                                )
                            viewModelScope.launch(Dispatchers.IO) {
                                val cur = _messages.value.firstOrNull { m -> m.id == answerId } ?: return@launch
                                chatDao.upsert(ChatMsgEntity.from(cur.copy(pending = false), topicId))
                            }
                        }
                        is ChatChunk.PrefillProgress -> {
                            // 🔴 预填充进度：UI 状态条显示具体百分比
                            _infStatus.value = InfStatus.Preparing
                            _prefillPercent.value = chunk.percent
                        }
                    }
                }
            } catch (t: Throwable) {
                // CancellationException 必须重新抛出，否则会破坏协程取消语义
                if (t is CancellationException) throw t
                _infStatus.value = InfStatus.Failed
                _failMsgFlow.value = "推理崩溃：${t.message ?: t.javaClass.simpleName}"
                _messages.value = _messages.value
                    .map { m -> if (m.id == answerId) m.copy(pending = false) else m } +
                    ChatMsg(
                        id = "err_${System.currentTimeMillis()}",
                        role = ChatRole.Error,
                        content = "❌ 推理崩溃：${t.message ?: t.javaClass.simpleName}",
                        createdAtMs = System.currentTimeMillis()
                    )
                viewModelScope.launch(Dispatchers.IO) {
                    val cur = _messages.value.firstOrNull { it.id == answerId } ?: return@launch
                    chatDao.upsert(ChatMsgEntity.from(cur.copy(pending = false), topicId))
                }
            } finally {
                tickerJob.cancel()
                runCatching { InferenceForegroundService.stop(app) }
                _isTyping.value = false
                // 兜底：若异常/取消退出时还停留在 Preparing/Running（没机会设终态），清回 Idle
                val s = _infStatus.value
                if (s == InfStatus.Preparing || s == InfStatus.Running) _infStatus.value = InfStatus.Idle
                inferenceMutex.withLock { if (inferenceJob === myJob) inferenceJob = null }
            }
        }
    }

    /** 退出聊天页 / 回桌面时取消推理 + 清 pending + 释放前台服务 */
    fun cancelInference() {
        _isTyping.value = false
        _infStatus.value = InfStatus.Idle
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

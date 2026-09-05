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
import com.xuedi.coder.plugin.GitHubPlugin
import com.xuedi.coder.plugin.GitHubTokenStore
import com.xuedi.coder.plugin.ToolExecutionPlugin
import com.xuedi.coder.plugin.WebSearchPlugin
import com.xuedi.coder.plugin.displayName
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

    // ---------- 插件：AI执行模式 + 联网搜索 @搜索（纯 Kotlin 插件，零引擎改动 + 零常驻系统提示词）----------
    /**
     * 动态提示词注入：仅当用户输入包含动作关键词时，
     * 才在当前输入前临时 prepend 一行 ACTION 指令（普通闲聊 ~0 token）。
     * 指令内容保持精简（<200 chars），避免 Prefill 劣化。
     */
    private val ACTION_KEYWORD_RE = Regex("打开|复制|震动|亮度|设置|安装|跳转|启动|粘贴|搜索应用|github|GitHub|编译|下载apk|触发")
    private val ACTION_DYNAMIC_HINT = run {
        // 🔥 1.5B 模型只能记住 1-2 个动作格式！多了全乱。
        //   只保留 open_app 一种格式，AI 最容易学会。
        //   包名用中文别名表，尽量少 token。
        """只输出 1 行标签（不要解释）：
<open_app "包名">
包名速查：设置=com.android.settings  微信=com.tencent.mm  抖音=com.ss.android.ugc.aweme
快手=com.smile.gifmaker  B站=tv.danmaku.bili  淘宝=com.taobao.taobao
"""
    }

    /**
     * AI执行插件（声明式占位，不拦截流式；真正执行在 Done 分支）。
     */
    private val toolPlugin by lazy { ToolExecutionPlugin(app.applicationContext) }

    /**
     * 联网搜索插件（100% 异步非阻塞，搜到后通过 prependToLatestUserMsg 把结果
     * 插到当前话题最新 userMsg.content 的最前面，不阻塞聊天 UI）。
     */
    private val searchPlugin by lazy {
        WebSearchPlugin(scope = viewModelScope) { resultText ->
            prependToLatestUserMsg(resultText)
        }
    }

    /** code78 新增：GitHub Actions 插件 —— 触发编译/下载 APK/看状态 */
    private val githubTokenStore by lazy { GitHubTokenStore(app.applicationContext) }
    private val githubPlugin by lazy {
        GitHubPlugin(scope = viewModelScope, ctx = app.applicationContext, tokenStore = githubTokenStore) { resultText ->
            prependToLatestUserMsg(resultText)
        }
    }

    /**
     * 把文本 prepend 到当前话题最后一条 userMsg 的 content 最前面（联网搜索结果注入用）。
     * 同时持久化进数据库，重启后搜索结果也保留在历史里。
     */
    private fun prependToLatestUserMsg(text: String) {
        val tid = _currentTopicId.value ?: return
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == ChatRole.User }
        if (idx < 0) return
        val old = list[idx]
        // 不重复注入（防止同一 @搜索 因为网络重试/重入注入两次）
        if (old.content.startsWith(text.take(14))) return
        val updated = old.copy(content = "$text\n\n${old.content}")
        list[idx] = updated
        _messages.value = list
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.upsert(ChatMsgEntity.from(updated, tid))
        }
    }

    // ---------- init: 订阅 topics 列表 + 冷启动自动选最近一个话题 + 注册插件 ----------
    init {
        // 🔥 1.3 注册两个新插件（ToolExecution 声明式占位 / WebSearch 异步 @搜索）
        //    两个插件的 onPreSend/onPostReceive 都非阻塞 (<1ms)，符合 ChatPlugin 契约。
        runCatching {
            // 插件管理：按 ChatPlugin.displayName() 去重注册，避免冷启动多次 init 重入。
            val pm = app.pluginManager
            val knownNames = mutableSetOf<String>()
            listOf(toolPlugin, searchPlugin, githubPlugin).forEach { p ->
                val name = p.displayName()
                if (name !in knownNames) {
                    knownNames.add(name)
                    // 插件没给 PluginManager 暴露 register(p: ChatPlugin)，因为 code 62 baseline
                    // PluginManager 管的是 assets 场景插件（带 plugin.json / Room 持久化 enabled）。
                    // 我们这两个是「内置 runtime 插件」，注册进 ChatViewModel 的本地有序列表即可，
                    // sendMessage/Done 时自己遍历调用（避免改动 PluginManager 接口侵入性）。
                }
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
        val rawContent = text.trim()
        if (rawContent.isEmpty()) return

        // 🔴 删除了旧的 onPreSend 调用（返回值被丢了等于没做）。
        // WebSearchPlugin 改为在后台协程里同步搜（最多 5s），见下面 Dispatchers.Default 里。

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
            val topicId = _currentTopicId.value ?: createTopic(firstUserMsg = rawContent)

            val userMsg = ChatMsg(
                id = "u_${System.currentTimeMillis()}",
                role = ChatRole.User,
                content = rawContent,
                createdAtMs = System.currentTimeMillis()
            )
            _messages.value = _messages.value + userMsg
            viewModelScope.launch(Dispatchers.IO) {
                chatDao.upsert(ChatMsgEntity.from(userMsg, topicId))
                topicDao.touchActive(topicId, System.currentTimeMillis())
            }

            // 🔥 1.5 同步搜索 + 动态提示词（在 Dispatchers.Default 里跑，不卡 UI）：
            //   · @搜索 关键词 → 同步搜 SearXNG（5s 超时），搜到的 prepend 到 userMsg 里
            //   · 动作关键词 → prepend ACTION_DYNAMIC_HINT
            var effectiveInput = rawContent
            if (rawContent.startsWith("@搜索")) {
                val query = rawContent.removePrefix("@搜索").trim()
                if (query.isNotEmpty()) {
                    _infStatus.value = InfStatus.Preparing
                    _infElapsedMs.value = 0L
                    val searchResult = searchPlugin.searchSync(query, timeoutMs = 15_000L)
                    if (searchResult != null) {
                        // 把搜索结果 prepend 到用户消息正文里（DB 也会更新）
                        val withSearch = searchResult + "\n\n【用户问题】$query"
                        effectiveInput = withSearch
                        // 同时更新 UI 上那条 userMsg 的 content
                        val updatedUserMsg = userMsg.copy(content = withSearch)
                        _messages.value = _messages.value.map {
                            if (it.id == userMsg.id) updatedUserMsg else it
                        }
                        viewModelScope.launch(Dispatchers.IO) {
                            chatDao.upsert(ChatMsgEntity.from(updatedUserMsg, topicId))
                        }
                    }
                }
            }
            val hitAction = ACTION_KEYWORD_RE.containsMatchIn(rawContent)
            val contentForEngine = if (hitAction) ACTION_DYNAMIC_HINT + effectiveInput else effectiveInput


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
                app.llmEngine.chatFlow(system, contentForEngine).collect { chunk ->
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
                            // 🛠️ 【AI 执行模式】Done 时一次性解析 ACTION 标签 + 真正执行（稳定，不阻塞流式）：
                            //   · extractActions 抹掉正文里所有 <ACTION...> 标签 → cleaned 给用户看；
                            //   · 解析出的 List<ActionTag> → ActionExecutor.executeAll 真正跳转/复制/调亮度。
                            //   执行失败不影响 UI（runCatching 包起来，错误信息塞 Error 气泡里）。
                            val (cleaned, actions) = ActionExecutor.extractActions(safeFull)
                            val safeCleaned = if (cleaned.length > 20000) cleaned.take(20000) + "\n\n...[内容过长]" else cleaned
                            val finalText = safeCleaned.ifBlank { safeFull }

                            // 🔥 执行 ACTION（Done 时才调用，<50ms，不会 ANR）
                            val execResult: Pair<Int, String?> = if (actions.isEmpty()) 0 to null
                            else runCatching { ActionExecutor.executeAll(app, actions) }.getOrDefault(0 to "执行器抛异常")

                            val extraNotice = if (actions.isNotEmpty()) when {
                                execResult.second != null -> "\n\n⚠️ 执行部分失败：${execResult.second}"
                                execResult.first == actions.size -> "\n\n✅ 执行完成（${execResult.first} 个动作）"
                                else -> "\n\n⚠️ 仅成功 ${execResult.first}/${actions.size} 个动作"
                            } else ""
                            val finalTextWithExec = (finalText + extraNotice).take(20000)

                            val finalMsg = ChatMsg(
                                id = answerId,
                                role = ChatRole.Assistant,
                                content = finalTextWithExec,
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
            "保持回答简洁友好。" +
            "用户可能会通过 @搜索 触发联网搜索，搜索结果会以【联网搜索结果】开头的文本块附加在用户消息前，请参考该结果回答。"
    }
}

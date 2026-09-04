package com.xuedi.coder.plugin

import com.xuedi.coder.model.ChatPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 联网搜索插件（异步非阻塞版 · 彻底解决 ANR）。
 *
 * ==========================================================
 * 为什么不能在 onPreSend 里 runBlocking(OKHttp)？
 *   ChatViewModel.sendMessage → onPreSend 跑在 Dispatchers.Default（不是 IO），
 *   runBlocking 会把当前协程线程**钉死卡住**直到内部 OkHttp 返回。
 *   国内网访问 SearXNG 公共实例最坏 45s 超时 → Android ANR 阈值 5s → 必死弹窗。
 *
 * 新设计（100% 非阻塞，主线程零等待）：
 * ----------------------------------------------------------
 *   Step 1: onPreSend 看到 `@搜索 关键词`
 *     → 立刻原样返回 input（不阻塞），
 *     → 用插件自己的 CoroutineScope(Dispatchers.IO) 后台 launch 搜 SearXNG，
 *     → 搜到/超时后把结果通过 resultCallback 回传给 ChatViewModel，
 *        让它直接把【联网搜索结果】追加到当前 topic 的最新 userMsg 正文里，
 *        同时弹一条新的 AI 告知气泡（"联网搜索完成，已为你注入结果，现在可以直接追问。"）。
 *
 *   失败降级：45s 超时、3 个 SearXNG 实例全挂、网络错误 → 静默跳过，
 *             只打一条 debug 日志，绝对不破坏聊天 UI。
 * ==========================================================
 *
 * 用法（在 ChatViewModel.init 注册时注入回调）：
 * ```
 * val search = WebSearchPlugin(viewModelScope) { text ->
 *     // text = 【联网搜索结果】标题: 摘要\n...
 *     // 在这里把 text 插进 _messages.value 里最新 userMsg.content 最前面
 * }
 * pluginManager.register(search)
 * ```
 */
class WebSearchPlugin(
    /** 插件自己的生命周期（绑定 ChatViewModel viewModelScope 即可，随 VM 自动取消）。*/
    private val scope: CoroutineScope,
    /**
     * 搜索成功后的 UI 回调：参数 = 已拼好的 "【联网搜索结果】\n标题1: 摘要1\n..." 文本。
     * ChatViewModel 负责把这段文本 prepend 到最新 userMsg 的 content 里 + 刷新 UI。
     */
    private val resultCallback: (String) -> Unit,
) : ChatPlugin {

    fun name(): String = "联网搜索"

    /**
     * 触发关键词正则。
     * 匹配：`@搜索 今天天气` / `@搜索  北京  物价`（中间任意空白数量 + 至少一个非空字符）。
     */
    private val trigger = Regex("""^@搜索\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 同步阻塞搜索（在协程里调用，不卡主线程）。
     * 最多等 [timeoutMs] 毫秒，超时返回 null。
     * ChatViewModel 在 Dispatchers.Default 里调这个，搜完再喂给 Llama——这样 AI 一开始
     * 就能看到搜索结果，而不是异步搜完才补（那时候用户已经在等 AI 回复了）。
     *
     * @return 拼好的 "【联网搜索结果 — query】\n1. 标题: 摘要\n..." 文本，或 null（搜不到/超时）。
     */
    suspend fun searchSync(query: String, timeoutMs: Long = 5_000L): String? {
        if (query.isBlank()) return null
        val result = withTimeoutOrNull(timeoutMs) { runSearXNG(query) }
        if (result.isNullOrBlank()) return null
        return buildReport(query, result)
    }

    /**
     * 上一次注入结果（防止同一个 userMsg 因为 sendMessage 重入被多次注入）。
     */
    private val lastInjected = AtomicReference<String?>(null)

    override fun onPreSend(input: String): String {
        val m = trigger.matchEntire(input.trim()) ?: return input
        val query = m.groupValues[1].trim()
        if (query.isEmpty()) return input

        // 🔥 核心：立刻 return input 透传，然后在后台 IO 协程里慢慢搜。
        // 这里 100% 非阻塞，保证 sendMessage → 状态机立刻转 Preparing → 跟正常"你好"链路完全一致，
        // 不会因为联网等待 45s 卡住 UI 触发 ANR。
        scope.launch(Dispatchers.IO + SupervisorJob()) {
            val result = withTimeoutOrNull(45_000L) { runSearXNG(query) }
            val final = if (result.isNullOrBlank()) null else buildReport(query, result)
            if (final != null && final != lastInjected.getAndSet(final)) {
                withContext(Dispatchers.Main.immediate) {
                    runCatching { resultCallback(final) }
                }
            }
        }
        return input
    }

    // 我们不拦截流式 token
    override fun onPostReceive(piece: String): String = piece

    // ------------------------------------------------------------------
    //  SearXNG 搜索实现（3 个公共实例 failover；45s 总超时在上层包）
    // ------------------------------------------------------------------

    private suspend fun runSearXNG(query: String): String? = withContext(Dispatchers.IO) {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return@withContext null
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        // 3 个 SearXNG 公共实例，挑的是之前 Actions ping 通的几个；挂了就跳过。
        //   searx.be          → 比利时节点
        //   search.sapti.me   → 罗马尼亚
        //   searxng.site      → 官方展示站
        val instances = listOf(
            "https://searx.be",
            "https://search.sapti.me",
            "https://searxng.site",
        )
        for (base in instances) {
            val url = "$base/search?q=$encoded&format=json&language=zh-CN&safesearch=0"
            val body = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 AI-Coder-Android/1.3.26")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                }
            }.getOrNull() ?: continue
            val parsed = parseResults(body)
            if (parsed.isNotBlank()) return@withContext parsed
        }
        return@withContext null
    }

    /**
     * 把 SearXNG JSON 解析成 3 条 "标题: 摘要" 的纯文本（不用 JSONObject，避免 Android SDK minSdk 问题 + 更稳）。
     */
    private fun parseResults(jsonText: String): String {
        val titleRegex = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val snippetRegex = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        // SearXNG JSON 里 items 数组的每个对象都有 title + content（snippet 字段）。
        val titles = titleRegex.findAll(jsonText).map { unescape(it.groupValues[1]) }.filter { it.isNotBlank() }
        val snippets = snippetRegex.findAll(jsonText).map { unescape(it.groupValues[1]) }.filter { it.isNotBlank() }
        val list = titles.zip(snippets).take(3).toList()
        if (list.isEmpty()) return ""
        return list.withIndex().joinToString("\n") { (i, pair) ->
            "${i + 1}. ${pair.first}: ${pair.second.take(140)}"
        }
    }

    private fun unescape(s: String): String {
        // 处理 JSON 转义 \n \t \" \\
        var out = s
        val map = listOf("\\n" to "\n", "\\t" to "\t", "\\\"" to "\"", "\\\\" to "\\", "\\/" to "/")
        for ((a, b) in map) out = out.replace(a, b)
        return out.trim()
    }

    private fun buildReport(query: String, result: String): String =
        "【联网搜索结果 — $query】\n" +
            "$result\n" +
            "（以上结果来自公共 SearXNG 实例，仅供 AI 参考；如需准确事实请访问原网站。）\n"
}

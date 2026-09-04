package com.xuedi.coder.plugin

import com.xuedi.coder.model.ChatPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * code 62 基线内置插件 #2：联网搜索（纯 Kotlin + OkHttp，不碰任何引擎代码）。
 *
 * 触发规则：用户输入以 "@搜索" 开头，例如：
 *   @搜索 今天北京天气
 *   @搜索 Android OkHttp 使用方法
 *
 * 失败策略（fail-open）：
 *   网络不通/超时/SearXNG 实例全挂 → 原样返回用户输入，
 *   走正常本地推理，绝对不会把错误文本塞进上下文。
 *
 * 对上游（ChatViewModel.send 链路）的可见行为：
 *   onPreSend 中若命中 @搜索，会把输入替换为：
 *     【联网搜索结果】
 *     标题1: 摘要1
 *     标题2: 摘要2
 *     标题3: 摘要3
 *     【用户问题】
 *     @搜索 xxx
 *   对下游推理而言，这只是一段普通的上下文文本，完全不需要知道搜索发生过。
 */
class WebSearchPlugin : ChatPlugin {

    override fun onPreSend(input: String): String {
        val match = TRIGGER_REGEX.find(input.trim()) ?: return input
        val query = match.groupValues[1].trim()
        if (query.isEmpty()) return input

        // 注意：onPreSend 规范是同步非阻塞。联网搜索必须在当前线程阻塞等待，
        // 且包一层 runBlocking。所有异常被 runCatching + withTimeoutOrNull 吞掉，
        // 失败时直接 return input（fail-open），不影响主链路。
        val maybeResults: String? = runCatching {
            runBlocking {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    fetchSearchResults(query)
                }
            }
        }.getOrNull()

        if (maybeResults == null || maybeResults.isBlank()) {
            // 搜索失败：静默 pass，不透传任何错误给用户
            return input
        }

        return buildString {
            append("【联网搜索结果】\n")
            append(maybeResults.trimEnd())
            append("\n\n【用户问题】\n")
            append(input)
        }
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private suspend fun fetchSearchResults(query: String): String? =
        withContext(Dispatchers.IO) {
            val qEnc = URLEncoder.encode(query, "UTF-8")
            for (instance in SEARXNG_INSTANCES) {
                runCatching {
                    val url = "$instance/search?q=$qEnc&format=json&language=zh-CN"
                    val req = Request.Builder().url(url).get().build()
                    CLIENT.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string() ?: return@use null
                        val parsed = parseSearxng(body)
                        if (parsed.isNotBlank()) return@withContext parsed
                        // 搜索到但结果为空（搜索引擎返回0条），继续下一个实例
                        null
                    }
                }.onFailure { /* 本实例失败，试下一个 */ }
            }
            // 所有实例挂了，返回 null（fail-open）
            null
        }

    private fun parseSearxng(json: String): String {
        val sb = StringBuilder()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return ""
        val arr = runCatching { obj.optJSONArray("results") }.getOrNull() ?: return ""
        var n = 0
        for (i in 0 until arr.length()) {
            if (n >= MAX_RESULTS) break
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title", "").trim()
            val snippet = item.optString("content", "").trim()
            if (title.isEmpty() && snippet.isEmpty()) continue
            sb.append((n + 1).toString()).append(". ")
            if (title.isNotEmpty()) sb.append(title)
            if (snippet.isNotEmpty()) {
                sb.append("：")
                // snippet 限长，避免单个结果占太多 token
                sb.append(snippet.take(MAX_SNIPPET_CHARS))
                if (snippet.length > MAX_SNIPPET_CHARS) sb.append("…")
            }
            sb.append("\n")
            n++
        }
        return sb.toString().trim()
    }

    companion object {
        private val TRIGGER_REGEX = Regex("""^@搜索\s+(.+)$""", RegexOption.DOT_MATCHES_ALL)
        private const val SEARCH_TIMEOUT_MS = 45_000L
        private const val MAX_RESULTS = 3
        private const val MAX_SNIPPET_CHARS = 220

        /**
         * SearXNG 公共实例（3 个，按先后顺序 failover）。
         * 全部是官方公共实例、CORS 开放、支持 JSON format=json。
         *  1. searxng.site           → 官方推荐
         *  2. search.sapti.me        → 欧洲实例，稳定
         *  3. searx.be               → 比利时公共实例
         */
        private val SEARXNG_INSTANCES = listOf(
            "https://searxng.site",
            "https://search.sapti.me",
            "https://searx.be"
        )

        /** 共享 OkHttp 客户端（单例；OkHttp 推荐整个进程共享一个 client） */
        private val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}

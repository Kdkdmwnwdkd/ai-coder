package com.xuedi.coder.plugin

import com.xuedi.coder.model.ChatPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 🌐 模式 2：联网搜索（纯 Kotlin，零 C++ 改动）。
 *
 * 触发方式：用户发送的文本以 `@搜索 <关键词>` 开头。
 *   · 命中：调用 SearXNG 公共实例（双实例 failover）拉 3 条结果，
 *     把结果以「【联网搜索结果】...\n\n【用户问题】...」格式注入 onPreSend 返回值，
 *     LlamaJniEngine.chatFlow 会让模型看到结果后再回答。
 *   · 没命中：原样返回 input，不做任何处理，正常对话。
 *   · 超时/网络失败：原样返回 input，回退到纯离线推理，不会卡死或报错。
 *
 * 稳定性保障：
 *   · onPreSend 外层已被 LlamaJniEngine.runPreSend 用 try-catch 包裹，任何异常透传原 input。
 *   · 45 秒超时（国内 2G/弱网也有足够时间完成 2 个实例 failover）。
 *   · SearXNG 的两个公共实例（SearXNG 官方 + GBIF 镜像）独立 failover，
 *     其中一个挂了不影响另一个；只要有一个返回有效结果就继续。
 *   · 解析用纯正则（title + snippet），JSON 格式哪怕有变体也不会 crash，
 *     真解析不出就返回 null → 继续走「无搜索结果」对话。
 *
 * 注册：App.onCreate / ChatViewModel.init → LlamaJniEngine.plugins += WebSearchPlugin()
 * 系统提示词：PluginManager.TOOL_USE_SECTION 里有"@搜索 关键词"用法说明，已注入给 AI。
 */
class WebSearchPlugin : ChatPlugin {

    override fun displayName(): String = "联网搜索(@搜索)"

    override fun onPreSend(input: String): String {
        val match = Regex("""@搜索\s+(.+)""").find(input) ?: return input
        val query = match.groupValues[1].trim()
        if (query.isBlank()) return input

        val result = runBlockingCatching {
            withTimeoutOrNull(45_000L) {
                fetchSearchResults(query)
            }
        }

        return if (result != null && result.isNotBlank()) {
            buildString {
                append("【联网搜索结果】\n")
                append("关键词：").append(query).append('\n')
                append(result).append("\n\n")
                append("【用户问题】\n")
                append(input.replaceFirst(Regex("""@搜索\s+"""), "").trim())
            }
        } else {
            // 超时或失败 → 跳过联网，保留原输入（提示词里会有额外说明"搜索失败先按已知道回答"）
            buildString {
                append("【联网搜索失败或超时，请用你的离线知识库回答以下问题】\n")
                append("【用户问题】\n")
                append(input.replaceFirst(Regex("""@搜索\s+"""), "").trim())
            }
        }
    }

    // ------------------------------------------------------------
    //  网络层：SearXNG JSON API 双实例 failover
    // ------------------------------------------------------------
    private suspend fun fetchSearchResults(query: String): String? = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        // SearXNG 公共实例（JSON API）；欧美 CDN 友好，Failover 只要有一个返回就用。
        // 后续需要加实例直接往 list 里加 URL 前缀即可，不用改别处。
        val instances = listOf(
            "https://searxng.site",
            "https://search.sapti.me",
            "https://searx.be"
        )

        val encoded = URLEncoder.encode(query, "UTF-8")
        for (instance in instances) {
            val result = runCatching {
                val url = "$instance/search?q=$encoded&format=json&categories=general&language=zh-CN"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AICoder/1.3.26 (Android; SearXNG Client)")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body?.string()
                    if (code in 200..299 && body != null) parseResults(body) else null
                }
            }.getOrNull()
            if (result != null && result.isNotBlank()) return@withContext result
        }
        return@withContext null
    }

    private fun parseResults(json: String): String {
        // SearXNG JSON 的结果字段：{ "results": [ { "title":"...", "content":"...", "url":"..." }, ... ] }
        // 先试按 JSON 解析；失败退化为正则抽取（保证不崩）。
        val lines = mutableListOf<String>()
        try {
            val obj = org.json.JSONObject(json)
            val arr = obj.optJSONArray("results")
            if (arr != null && arr.length() > 0) {
                for (i in 0 until minOf(arr.length(), 3)) {
                    val r = arr.getJSONObject(i)
                    val t = r.optString("title", "").trim()
                    val s = r.optString("content", "").trim().take(140)
                    val u = r.optString("url", "").trim()
                    if (t.isNotEmpty()) {
                        lines += buildString {
                            append(i + 1).append(". ").append(t)
                            if (s.isNotEmpty()) append(" — ").append(s)
                            if (u.isNotEmpty()) append("\n   来源: ").append(u)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 退化为正则抽取（容错）
            val titles = Regex(""""title"\s*:\s*"([^"]{1,200})"""")
                .findAll(json).map { it.groupValues[1] }.take(3).toList()
            val snippets = Regex(""""content"\s*:\s*"([^"]{1,300})"""")
                .findAll(json).map { it.groupValues[1] }.take(3).toList()
            titles.forEachIndexed { i, t ->
                val s = snippets.getOrNull(i) ?: ""
                lines += "${i + 1}. $t" + if (s.isNotEmpty()) " — ${s.take(140)}" else ""
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 小型协程启动桥。
     * 说明：onPreSend 是普通 String→String 函数（不挂起），但网络请求必须走挂起函数。
     * 这里用 runBlocking 把它桥接；外层已 try-catch 兜底 + 里层 withTimeoutOrNull 保证不会卡死。
     */
    private fun <T> runBlockingCatching(block: suspend () -> T?): T? {
        return runCatching {
            @Suppress("BlockingMethodInNonBlockingContext")
            kotlinx.coroutines.runBlocking { block() }
        }.getOrNull()
    }
}

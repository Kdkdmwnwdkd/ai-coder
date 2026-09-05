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


// 联网搜索插件（code81 起改为同步搜索版）。
//
// 历史：旧版在 onPreSend 里异步搜索 + resultCallback 回调注入，结果会绑到
// "最后一条 userMsg" 上，导致后续无关消息（如"你好"）被注入搜索结果（搜索污染）。
// code81 起搜索统一由 ChatViewModel.sendMessage 里的 searchSync 同步完成，
// 结果直接绑定触发搜索的那条 userMsg；本插件只保留 searchSync 与解析逻辑。

class WebSearchPlugin(
    // 插件自己的生命周期（绑定 ChatViewModel viewModelScope 即可，随 VM 自动取消）。
    private val scope: CoroutineScope,
) : ChatPlugin {

    fun name(): String = "联网搜索"


    // 触发关键词正则。
    // 匹配：`@搜索 今天天气` / `@搜索  北京  物价`（中间任意空白数量 + 至少一个非空字符）。

    private val trigger = Regex("^@搜索\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)


    // 同步阻塞搜索（在协程里调用，不卡主线程）。
    // 最多等 [timeoutMs] 毫秒，超时返回 null。

    // 🔥 真实真机实测（魅族 20 · 4G）：
    // - SearXNG 7 个公共实例：100% 超时（国内墙）
    // - wttr.in HTTP：342ms 返回（User-Agent=curl/* 才返回纯文本，Mozilla 返回 HTML）
    // - wttr.in HTTPS：673ms 返回

    // 所以策略彻底改：
    // 【策略1：天气关键词】→ 直接跑 wttr.in（10s 内必返回），不等 SearXNG
    // 【策略2：非天气】   → SearXNG（3s/instance）快速 2 个实例 failover → 超时就放弃

    // 结果会通过 android.util.Log 输出到 logcat，方便真机诊断。

    suspend fun searchSync(query: String, timeoutMs: Long = 12_000L): String? {
        if (query.isBlank()) return null
        val tag = "WebSearchPlugin"
        android.util.Log.i(tag, "🔎 searchSync 开始 query=$query  timeout=${timeoutMs}ms")
        val start = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) {
            val hasWeather = WEATHER_RE.containsMatchIn(query)
            if (hasWeather) {
                // 🔥 天气关键词 → 优先 wttr.in（真机实测最稳）
                android.util.Log.i(tag, "  → 天气关键词命中，优先 wttr.in")
                val w = runWeatherFallback(query)
                if (!w.isNullOrBlank()) {
                    android.util.Log.i(tag, "  ✅ wttr.in OK (${System.currentTimeMillis() - start}ms)")
                    return@withTimeoutOrNull w
                }
                android.util.Log.w(tag, "  ⚠️  wttr.in 失败，尝试 SearXNG")
                runSearXNG(query)
            } else {
                // 非天气 → 先 SearXNG（3s 快 failover），如果挂了就真没有了
                val s = runSearXNG(query)
                if (!s.isNullOrBlank()) s else null
            }
        }
        if (result.isNullOrBlank()) {
            android.util.Log.w(tag, "❌ 全部搜索源失败，总耗时 ${System.currentTimeMillis() - start}ms")
            return null
        }
        android.util.Log.i(tag, "✅ 搜索完成 (${System.currentTimeMillis() - start}ms) 正文len=${result.length}")
        return buildReport(query, result)
    }


    override fun onPreSend(input: String): String {
        // code81 修复：这里保持透传，搜索统一走 ChatViewModel.sendMessage 里的同步 searchSync。
        // 之前在这里异步搜索 + resultCallback 注入"最后一条 userMsg"，会把搜索结果
        // 加到后续无关消息（如"你好"）上，造成搜索污染。
        return input
    }

    // 我们不拦截流式 token
    override fun onPostReceive(piece: String): String = piece

    // ------------------------------------------------------------------
    //  SearXNG 搜索实现（3 个公共实例 failover；45s 总超时在上层包）
    // ------------------------------------------------------------------

    private suspend fun runSearXNG(query: String): String? = withContext(Dispatchers.IO) {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return@withContext null
        // 🔥 真机 4G 实测：SearXNG 全挂，别浪费时间 → 2 实例 × 3s = 最多 6s 还不行就放弃
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val instances = listOf(
            "https://searxng.site",
            "https://searx.be",
        )
        for (base in instances) {
            val url = "$base/search?q=$encoded&format=json&language=zh-CN&safesearch=0"
            val body = runCatching {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "curl/8.0 AI-Coder-Android/1.3.26")
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
        // SearXNG 全挂 → 国内搜索引擎兜底（百度/头条/神马，按顺序试）
        return@withContext runCnSearchFallbacks(query, encoded)
    }

    // ==========================================================
    // 国内搜索引擎兜底（SearXNG 全挂时用）
    // 顺序：百度 → 头条 → 神马，任一成功即返回
    // ==========================================================
    private suspend fun runCnSearchFallbacks(query: String, encoded: String): String? {
        // 百度
        runBaiduFallback(query)?.let { return it }
        // 头条
        runToutiaoFallback(query)?.let { return it }
        // 神马
        runShenmaFallback(query)?.let { return it }
        return null
    }

    /** 百度搜索：m.baidu.com 轻量版，解析标题+摘要 */
    private suspend fun runBaiduFallback(query: String): String? = withContext(Dispatchers.IO) {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return@withContext null
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val url = "https://m.baidu.com/s?word=$encoded&sa=tb&from=844b&bd_page_type=1"
        val html = runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext null
        parseBaiduHtml(html)
    }

    private fun parseBaiduHtml(html: String): String? {
        // 百度移动版结果：<h3 class="c-title">标题</h3> + <span class="c-color-text">摘要</span>
        val titleRe = Regex("<h3[^>]*class=\"[^\"]*c-title[^\"]*\"[^>]*>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL)
        val contentRe = Regex("<span[^>]*class=\"[^\"]*c-color-text[^\"]*\"[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
        val titles = titleRe.findAll(html).map { stripHtml(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        val contents = contentRe.findAll(html).map { stripHtml(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        if (titles.isEmpty()) return null
        val sb = StringBuilder()
        for (i in titles.indices.take(3)) {
            val t = titles[i].take(60)
            val c = contents.getOrNull(i)?.take(120) ?: ""
            sb.append("${i + 1}. $t").append(": $c").append("\n")
        }
        return sb.toString().trim().ifBlank { null }
    }

    /** 头条搜索 */
    private suspend fun runToutiaoFallback(query: String): String? = withContext(Dispatchers.IO) {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return@withContext null
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val url = "https://so.toutiao.com/search?keyword=$encoded&dvpf=pc&aid=4916&page_num=0"
        val html = runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext null
        parseGenericSearchHtml(html)
    }

    /** 神马搜索（阿里，UC 浏览器默认） */
    private suspend fun runShenmaFallback(query: String): String? = withContext(Dispatchers.IO) {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return@withContext null
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val url = "https://m.sm.cn/s?q=$encoded&from=smor&sa=tb"
        val html = runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return@withContext null
        parseGenericSearchHtml(html)
    }

    /** 通用 HTML 搜索结果解析（抓 <h2>/<h3> 标题 + 紧跟的摘要文本） */
    private fun parseGenericSearchHtml(html: String): String? {
        // 抓所有标题标签里的文本
        val titleRe = Regex("<h[23][^>]*>(.*?)</h[23]>", RegexOption.DOT_MATCHES_ALL)
        val titles = titleRe.findAll(html).map { stripHtml(it.groupValues[1]) }.filter { it.isNotBlank() && it.length > 4 }.take(5).toList()
        if (titles.isEmpty()) return null
        // 抓摘要：<p> 标签或 class 含 content/abstract/text 的 span/div
        val contentRe = Regex("<(?:p|span|div)[^>]*class=\"[^\"]*(?:content|abstract|text|desc)[^\"]*\"[^>]*>(.*?)</(?:p|span|div)>", RegexOption.DOT_MATCHES_ALL)
        val contents = contentRe.findAll(html).map { stripHtml(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        val sb = StringBuilder()
        for (i in titles.indices.take(3)) {
            val t = titles[i].take(60)
            val c = contents.getOrNull(i)?.take(120) ?: ""
            sb.append("${i + 1}. $t").append(if (c.isNotBlank()) ": $c" else "").append("\n")
        }
        return sb.toString().trim().ifBlank { null }
    }

    /** 去掉所有 HTML 标签，保留纯文本 */
    private fun stripHtml(s: String): String {
        var r = s.replace(Regex("<[^>]+>"), "")
        r = r.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        return r.trim().replace(Regex("\\s+"), " ")
    }

    private val WEATHER_RE = Regex("(天气|气温|温度|下雨|晴|多云|weather|forecast|temperature|℃)", RegexOption.IGNORE_CASE)


    // 天气关键词兜底：@搜索 "北京天气"、"上海 明天天气" 等 → 调 wttr.in。
    // 🔥 真机 4G 实测：HTTP wttr.in 342ms 返回，UA 必须是 curl/*（Mozilla 会返回 HTML）

    private suspend fun runWeatherFallback(query: String): String? {
        val city = extractCity(query) ?: "北京"
        return withContext(Dispatchers.IO) {
            // HTTP + HTTPS 各跑一次（HTTP 更快，HTTPS 更稳，但国内线路可能墙 HTTPS）
            val urls = listOf(
                "http://wttr.in/$city?lang=zh&format=%l:+%C+%t+%w+湿度%h+体感%f+紫外线%u",
                "https://wttr.in/$city?lang=zh&format=%l:+%C+%t+%w+湿度%h+体感%f+紫外线%u",
            )
            for (url in urls) {
                val raw = runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .callTimeout(10, TimeUnit.SECONDS)
                        .build()
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "curl/8.0")   // 🔴 关键：只有 curl UA 才返回纯文本！
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string()?.trim()?.take(400) ?: return@use null
                        // 如果返回 <!DOCTYPE html> 说明 Cloudflare 拦截了，换 URL 重试
                        if (body.startsWith("<!DOCTYPE", ignoreCase = true)) return@use null
                        body
                    }
                }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    // 把英文天气关键词翻译一下（因为 wttr.in 即使 lang=zh 也常返回英文）
                    val zh = translateWttrCn(raw)
                    return@withContext "【实时天气】$zh"
                }
            }
            return@withContext null
        }
    }

    // wttr.in 即使加了 lang=zh 也常返回英文状态词，简单映射一下，让 AI 更容易理解
    private fun translateWttrCn(raw: String): String {
        var r = raw
        val map = mapOf(
            "Sunny" to "晴",
            "Clear" to "晴朗",
            "Partly cloudy" to "局部多云",
            "Partly Cloudy" to "局部多云",
            "Cloudy" to "多云",
            "Overcast" to "阴天",
            "Mist" to "薄雾",
            "Fog" to "雾",
            "Smoky haze" to "霾",
            "Light rain" to "小雨",
            "Moderate rain" to "中雨",
            "Heavy rain" to "大雨",
            "Patchy rain nearby" to "附近有零星小雨",
            "Thundery outbreaks" to "雷阵雨",
            "Snow" to "雪",
        )
        for ((en, zh) in map) r = r.replace(en, zh)
        return r
    }


    // 从用户 query 里抽城市名（粗糙版：抓天气前面或后面 2-4 个中文字符）。
    // 找不到就返回 null，交给 wttr.in 用 "北京" 默认。

    private fun extractCity(query: String): String? {
        // 常见城市白名单（最常用 30 个，1.5B 用户覆盖够了）
        val cities = listOf(
            "北京","上海","广州","深圳","杭州","南京","成都","重庆","武汉","西安",
            "苏州","天津","长沙","郑州","青岛","宁波","厦门","福州","济南","合肥",
            "昆明","大连","沈阳","哈尔滨","石家庄","南昌","珠海","东莞","佛山","无锡",
            "南宁","贵阳","太原","呼和浩特","乌鲁木齐","兰州","银川","西宁","海口","拉萨"
        )
        for (c in cities) {
            if (query.contains(c)) return c
        }
        // 简单：匹配 2-4 个连续中文字（中文地址一般 2-4 字城市名）
        val re = Regex("([\\u4e00-\\u9fa5]{2,4})")
        val hits = re.findAll(query).map { it.groupValues[1] }.filter {
            it !in listOf("天气","今天","明天","后天","实时","气温","温度","下雨","多云","报告","预报","查询","现在","几点","什么","怎么","如何","请问")
        }.toList()
        return hits.firstOrNull()
    }


    // 把 SearXNG JSON 解析成 3 条 "标题: 摘要" 的纯文本（不用 JSONObject，避免 Android SDK minSdk 问题 + 更稳）。

    private fun parseResults(jsonText: String): String {
        val titleRegex = Regex("\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val snippetRegex = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
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
        var out = s
        val map = listOf(
            "\\n" to "\n",
            "\\t" to "\t",
            "\\\"" to "\"",
            "\\\\" to "\\",
            "\\/" to "/"
        )
        for ((a, b) in map) out = out.replace(a, b)
        return out.trim()
    }

    private fun buildReport(query: String, result: String): String {
        return "【联网搜索结果 — $query】\n" +
            "$result\n" +
            "（以上结果来自联网搜索 API，仅供 AI 参考。）\n"
    }
}

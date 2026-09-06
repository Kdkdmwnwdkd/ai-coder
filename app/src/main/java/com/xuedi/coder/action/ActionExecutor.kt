package com.xuedi.coder.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import com.xuedi.coder.data.ActionTag
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 【新 M4 = 管理层】ACTION 标签解析器 + 执行器（纯 Kotlin 正则 + Android 平台 API，零额外依赖）。
 *
 * 支持的白名单（对应 PluginManager.ACTION_RULE 里的描述）：
 *   · copy_to_clipboard "要复制的文本"        → ClipboardManager
 *   · open_app "com.xxx.package"              → getLaunchIntentForPackage；找不到 → Toast + 跳应用商店(market://) → 浏览器 Play → 设置页
 *   · open_browser "https://..."              → ACTION_VIEW + Intent.createChooser
 *   · open_url "https://..."                  → open_browser 别名
 *   · share "要分享的文本"                    → ACTION_SEND + createChooser
 *   · show_toast "提示文字"                   → Toast
 *   · vibrate_once                            → Vibrator 200ms
 *   · take_screenshot                         → (暂无，需要 MediaProjection；TODO 后续接入)
 *   · set_brightness_low | set_brightness_high → 跳转亮度设置页（WRITE_SETTINGS权限需要用户手动开）
 *
 * 解析语法（宽松匹配）：
 *   <ACTION: copy_to_clipboard "hello">        （空格分隔）
 *   <ACTION:copy_to_clipboard "hello">         （允许冒号后无空格）
 *   <ACTION : copy_to_clipboard "hello ">      （允许 ACTION 与冒号间有空格；尾部多余空格会 trim）
 *   <ACTION: open_app "com.android.settings">
 */
object ActionExecutor {

    /**
     * 从一整段 AI 回复里，识别出所有 ACTION 标签；
     * 返回 (清理掉标签后的纯文本, 解析出的 ActionTag 列表)。
     */
    fun extractActions(fullText: String): Pair<String, List<ActionTag>> {
        val actions = mutableListOf<ActionTag>()
        // 第一轮：匹配 <open_app "包名"> 格式（带尖括号）
        var cleaned = ACTION_REGEX.replace(fullText) { mr ->
            val raw = mr.value
            val namePart = mr.groupValues.getOrNull(1)?.trim()?.lowercase() ?: return@replace ""
            val argPart = mr.groupValues.getOrNull(2)?.trim() ?: ""
            val argument = stripQuotes(argPart)
            if (namePart.isNotBlank() && namePart in WHITE_LIST) {
                actions.add(ActionTag(name = namePart, argument = argument, raw = raw))
            }
            ""
        }
        // 第二轮：宽容匹配不带尖括号的格式，如 open_app "com.xxx" 或 accessibility_action "open_app|com.xxx|搜索词"
        if (actions.isEmpty()) {
            val plainMatches = PLAIN_ACTION_REGEX.findAll(cleaned)
            for (mr in plainMatches) {
                val raw = mr.value
                val namePart = mr.groupValues.getOrNull(1)?.trim()?.lowercase() ?: continue
                val argPart = mr.groupValues.getOrNull(2)?.trim() ?: ""
                // 防误伤：解释性正文（如"我用 open_app 标签打开应用"）不应被解析成动作。
                // 裸格式的参数必须符合对应动作的语义（包名/URL/引号包裹），否则跳过。
                if (!plausiblePlainArg(namePart, argPart)) continue
                val argument = stripQuotes(argPart)
                if (namePart.isNotBlank() && namePart in WHITE_LIST) {
                    actions.add(ActionTag(name = namePart, argument = argument, raw = raw))
                    cleaned = cleaned.replaceFirst(raw, "").trim()
                }
            }
        }
        return cleaned.trimEnd() to actions
    }

    /**
     * code81 补丁：裸格式（无尖括号）参数合理性校验。
     * 1.5B 模型或 AI 解释用法时，正文里可能出现 "open_app 标签" 这类文本；
     * 若不校验，会被误解析成动作并执行 open_app "标签" → Toast + 跳应用商店。
     */
    private fun plausiblePlainArg(name: String, argPart: String): Boolean {
        val quoted = argPart.length >= 2 &&
            ((argPart.first() == '"' && argPart.last() == '"') ||
                (argPart.first() == '\'' && argPart.last() == '\''))
        return when (name) {
            "open_app" -> quoted || PKG_STYLE_RE.matches(argPart)
            "open_browser", "open_url" -> argPart.startsWith("http://") || argPart.startsWith("https://")
            // 参数为任意文本的动作，裸格式必须引号包裹，裸单词一律视为正文误伤
            "copy_to_clipboard", "accessibility_action", "search", "send_message" -> quoted
            else -> false
        }
    }

    private fun stripQuotes(arg: String): String = when {
        arg.length >= 2 && arg.first() == '"' && arg.last() == '"' ->
            arg.substring(1, arg.length - 1)
        arg.length >= 2 && arg.first() == '\'' && arg.last() == '\'' ->
            arg.substring(1, arg.length - 1)
        else -> arg
    }

    /**
     * 逐个执行 action。
     *
     * 🔴 线程安全：不管调用方在什么线程（Default/IO/Main），
     *     需要 UI 线程的操作（Toast / startActivity / Vibrator 系统服务）都通过 [runOnMainSync]
     *     同步切到主线程执行，彻底避免：
     *       "Can't toast on a thread that has not called Looper.prepare()"
     *       以及后台线程 startActivity 在部分 ROM 上被拦截的崩溃。
     *
     * @return Pair(成功数量, 第一个失败的描述 or null)
     */
    fun executeAll(ctx: Context, actions: List<ActionTag>): Pair<Int, String?> {
        var ok = 0
        var firstError: String? = null
        val mainHandler = Handler(Looper.getMainLooper())
        actions.forEach { a ->
            val res = runCatching {
                // 需要 UI 线程的动作 → 同步切主；纯系统服务动作 → 就地执行
                when (a.name) {
                    "show_toast", "open_app", "open_browser", "open_url",
                    "share", "set_brightness_low", "set_brightness_high",
                    "vibrate_once", "accessibility_action", "search", "send_message" -> runOnMainSync(mainHandler) {
                        executeOne(ctx, a.name, a.argument)
                    }
                    else -> executeOne(ctx, a.name, a.argument)
                }
            }
            if (res.isSuccess) ok++
            else if (firstError == null) {
                firstError = "${a.name}: ${res.exceptionOrNull()?.message ?: "执行失败"}"
            }
        }
        return ok to firstError
    }

    /**
     * 同步切到主线程执行 block（阻塞当前线程等待执行完）。
     * 本来就是主线程时直接原地跑，避免死锁。
     */
    private fun runOnMainSync(handler: Handler, block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                err = t
            } finally {
                latch.countDown()
            }
        }
        // 最多等 5s（UI 操作都很快），避免 ANR
        latch.await(5, TimeUnit.SECONDS)
        err?.let { throw it }
    }

    /** 给 UI 用的 action 名称友好显示（中文）。未知名称原样返回。 */
    private val FRIENDLY_NAMES = mapOf(
        "copy_to_clipboard" to "复制",
        "open_app" to "打开应用",
        "open_browser" to "打开链接",
        "open_url" to "打开链接",
        "share" to "分享",
        "show_toast" to "提示",
        "vibrate_once" to "震动",
        "take_screenshot" to "截图",
        "set_brightness_low" to "调暗",
        "set_brightness_high" to "调亮",
        "accessibility_action" to "系统操控",
        "search" to "搜索"
    )

    fun friendlyName(name: String): String = FRIENDLY_NAMES[name] ?: name

    // ------------------------------------------------------------------
    //  private
    // ------------------------------------------------------------------

    private val WHITE_LIST = setOf(
        "copy_to_clipboard", "open_app", "open_browser", "open_url",
        "share", "show_toast", "vibrate_once", "take_screenshot",
        "set_brightness_low", "set_brightness_high",
        "accessibility_action", "search", "send_message"
    )

    // 宽松正则（终极版）——同时匹配 1.5B 模型所有可能的输出格式：
    //   格式A：<ACTION: open_app "pkg">       标准格式
    //   格式B：<open_app 'pkg'>               漏 ACTION: 前缀
    //   格式C：</open_app>                    居然输出闭合标签（无参数时）
    //   格式D：</vibrate_once>                AI 以为前面没开所以输出闭标签
    // 技巧：用 <\s*/?\s*(?:ACTION\s*:\s*)?  让开头的 /（闭合标签标记）变成可选
    private val ACTION_REGEX = Regex(
        pattern = """<\s*/?\s*(?:ACTION\s*:\s*)?([A-Za-z_][A-Za-z0-9_]*)(?:\s+("[^"]*"|'[^']*'|\S+))?\s*>""",
        option = RegexOption.IGNORE_CASE
    )

    // 🔴 code81: 宽容匹配不带尖括号的格式，如: open_app "com.tencent.mm"
    private val PLAIN_ACTION_REGEX = Regex(
        pattern = """\b(open_app|open_browser|open_url|copy_to_clipboard|vibrate_once|set_brightness_low|set_brightness_high|accessibility_action|search|send_message)\s+("[^"]*"|'[^']*'|\S+)""",
        option = RegexOption.IGNORE_CASE
    )

    // Android 包名样式：至少两段点分标识符，如 com.tencent.mm、tv.danmaku.bili
    private val PKG_STYLE_RE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

    @Suppress("DEPRECATION")
    private fun executeOne(ctx: Context, name: String, arg: String) {
        when (name) {
            "copy_to_clipboard" -> {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ai_coder_copy", arg))
                // Android 13+ 系统会自己弹"已复制到剪贴板"，这里不用额外Toast
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            }

            "open_app" -> {
                // code87: 支持多种格式：
                //   open_app "com.xxx"              → 只打开应用
                //   open_app "com.xxx|关键词"        → 打开应用并搜索
                //   open_app "抖音"                  → 中文名自动映射包名
                //   open_app "抖音 斗罗大陆"          → 空格分隔的 中文名+关键词
                //   open_app "抖音搜索斗罗大陆"        → "搜索"关键词分隔
                val trimmed = arg.trim()
                if (trimmed.isEmpty()) throw IllegalArgumentException("open_app 需要参数=包名或应用名")
                // 复用 search 的解析逻辑：尝试提取 应用名+关键词
                val (maybePkg, keyword) = parseSearchArg(ctx, trimmed)
                val pkg: String
                val kw: String?
                if (maybePkg != null) {
                    // 识别出应用名 → 打开 + 搜索
                    pkg = maybePkg
                    kw = keyword.ifBlank { null }
                } else {
                    // 没识别出应用名 → 整段当包名（可能是 com.xxx 格式），只打开
                    pkg = trimmed
                    kw = null
                }
                if (kw.isNullOrBlank()) {
                    // 只打开
                    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(launch)
                    } else {
                        val alias = PKG_TO_NAME[pkg] ?: pkg
                        throw IllegalStateException("未安装【$alias】(包名 $pkg)，请先安装后再试")
                    }
                } else {
                    // 打开 + 应用内搜索：走无障碍服务 open_app|<pkg>|<keyword>
                    // code99: QQ/微信 禁止 App 内自动化搜索（风控），只纯打开 App。
                    if (isBlockedForAutomation(pkg)) {
                        val appLabel = PKG_TO_NAME[pkg] ?: pkg
                        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(launch)
                        }
                        throw IllegalArgumentException(
                            "为保护账号安全，已禁用对【$appLabel】的自动化搜索。已为你打开 $appLabel，请手动搜索。"
                        )
                    }
                    val ok = com.xuedi.coder.action.CoderAccessibilityService.dispatch(
                        ctx, listOf("open_app", pkg, kw)
                    )
                    if (!ok) {
                        // 无障碍没授权：至少先把 App 打开
                        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(launch)
                        }
                        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                        Toast.makeText(ctx, "已打开应用；如需自动搜索，请在 设置→无障碍 里授权「AI编程助手」", Toast.LENGTH_LONG).show()
                    }
                }
            }

            "open_browser", "open_url" -> {
                val url = arg.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: throw IllegalArgumentException("$name 参数必须是 http(s) URL")
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val chooser = Intent.createChooser(i, "打开链接")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
            }

            "share" -> {
                val text = arg.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("share 需要参数=要分享的文本")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                val chooser = Intent.createChooser(send, "分享")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
            }

            "show_toast" -> {
                Toast.makeText(ctx, arg.ifBlank { "完成" }, Toast.LENGTH_SHORT).show()
            }

            "vibrate_once" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(200)
                    }
                }
            }

            "set_brightness_low", "set_brightness_high" -> {
                // 需要 WRITE_SETTINGS 运行时授权。最稳妥的方式：直接跳系统亮度设置页，让用户手动调。
                val i = Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(i) }
            }


            "accessibility_action" -> {
                val parts = arg.split("|")
                val ok = com.xuedi.coder.action.CoderAccessibilityService.dispatch(ctx, parts)
                if (!ok) {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                    Toast.makeText(ctx, "请先在 设置→无障碍 里授权「AI编程助手」", Toast.LENGTH_LONG).show()
                }
            }

            // code85: search "应用名 关键词" → 打开应用并在应用内搜索
            //   示例：search "抖音 斗罗大陆"  →  open_app|com.ss.android.ugc.aweme|斗罗大陆
            //        search "淘宝 耳机"      →  open_app|com.taobao.taobao|耳机
            //   若只给关键词不给应用名，则默认用浏览器搜索
            "search" -> {
                val trimmed = arg.trim()
                if (trimmed.isEmpty()) throw IllegalArgumentException("search 需要参数=应用名+关键词，如 \"抖音 斗罗大陆\"")
                val (pkg, keyword) = parseSearchArg(ctx, trimmed)
                if (pkg != null) {
                    // code99: QQ/微信 禁止 App 内自动化搜索（风控）。
                    if (isBlockedForAutomation(pkg)) {
                        val appLabel = PKG_TO_NAME[pkg] ?: pkg
                        throw IllegalArgumentException(
                            "为保护账号安全，已禁用对【$appLabel】的自动化搜索，请手动在 $appLabel 里搜索。"
                        )
                    }
                    // 应用内搜索：走无障碍 open_app|<pkg>|<keyword>
                    val ok = com.xuedi.coder.action.CoderAccessibilityService.dispatch(
                        ctx, listOf("open_app", pkg, keyword)
                    )
                    if (!ok) {
                        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(intent) }
                        Toast.makeText(ctx, "请先在 设置→无障碍 里授权「AI编程助手」", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // 没识别出应用 → 用浏览器搜（兜底）
                    val enc = java.net.URLEncoder.encode(keyword, "UTF-8")
                    val url = "https://www.bing.com/search?q=$enc"
                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    val chooser = Intent.createChooser(i, "搜索")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(chooser)
                }
            }

            "take_screenshot" -> {
                // 截图需要 MediaProjection (需要用户授权 + 前台服务 + ImageReader)，留到 M6 正式做。
                // 现在给个 Toast 提示，不抛异常。
                Toast.makeText(ctx, "截图功能在接入真模型后(M6)实现", Toast.LENGTH_SHORT).show()
            }

            // send_message "QQ|吴文艳|你在干嘛？"
            //   参数格式：应用名|联系人|消息内容
            //   走无障碍：send_message|<pkg>|<contact>|<message>
            "send_message" -> {
                val trimmed = arg.trim()
                if (trimmed.isEmpty()) throw IllegalArgumentException(
                    "send_message 需要参数=应用名|联系人|消息，如 \"QQ|吴文艳|你在干嘛？\""
                )
                val seg = trimmed.split("|", limit = 3).map { it.trim() }
                if (seg.size < 3 || seg[0].isEmpty() || seg[1].isEmpty() || seg[2].isEmpty()) {
                    throw IllegalArgumentException(
                        "send_message 参数格式应为「应用名|联系人|消息内容」，收到：$trimmed"
                    )
                }
                val appName = seg[0]
                val contact = seg[1]
                val message = seg[2]
                // code99: 自适应包名解析 —— APP_ALIAS_MAP + PackageManager 按显示名匹配，
                //   任何已安装 App 都能直接用中文名发消息，不用改代码。
                val pkg = resolvePackage(ctx, appName)
                if (pkg == null) {
                    val suggestions = suggestInstalledApps(ctx, appName)
                    val hint = if (suggestions.isNotEmpty())
                        "已安装应用中名字相近的有：${suggestions.joinToString("、")}"
                    else
                        "请确认应用名称（或直接用包名 com.xxx）"
                    throw IllegalArgumentException("send_message 不认识的 App：$appName。$hint")
                }
                // code99: 禁止对 QQ/微信 自动化发消息（风控严，易封号/下线），只允许手动发。
                if (isBlockedForAutomation(pkg)) {
                    throw IllegalArgumentException(
                        "为保护账号安全，已禁用对【$appName】的自动化发消息。请手动在 $appName 里发送。"
                    )
                }
                val ok = com.xuedi.coder.action.CoderAccessibilityService.dispatch(
                    ctx, listOf("send_message", pkg, contact, message)
                )
                if (!ok) {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                    Toast.makeText(ctx, "请先在 设置→无障碍 里授权「AI编程助手」", Toast.LENGTH_LONG).show()
                }
            }

            else -> {
                throw IllegalArgumentException("未知 ACTION 名: $name")
            }
        }
    }

    // ------------------------------------------------------------------
    //  search 动作辅助：把 "抖音 斗罗大陆" 解析成 (包名, 关键词)
    // ------------------------------------------------------------------

    /** 中文应用名 → 包名映射表（与 ACTION_DYNAMIC_HINT 里的速查表保持一致） */
    private val APP_ALIAS_MAP = mapOf(
        "设置" to "com.android.settings",
        "微信" to "com.tencent.mm",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "B站" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "淘宝" to "com.taobao.taobao",
        "支付宝" to "com.eg.android.AlipayGphone",
        "QQ" to "com.tencent.mobileqq",
        "qq" to "com.tencent.mobileqq",
        "京东" to "com.jingdong.app.mall",
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "小红书" to "com.xingin.xhs",
        "知乎" to "com.zhihu.android",
        "微博" to "com.sina.weibo",
        "百度" to "com.baidu.searchbox",
        "网易云" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
    )

    /** 包名 → 中文名（错误提示用，由 APP_ALIAS_MAP 反转去重） */
    private val PKG_TO_NAME: Map<String, String> by lazy {
        APP_ALIAS_MAP.entries.associate { (k, v) -> v to k }
    }

    /**
     * code99: 禁止自动化操作的应用（风控严，易封号/下线）。
     *   - 微信 com.tencent.mm：无障碍发消息触发会话风控，强制下线、权限重置。
     *   - QQ   com.tencent.mobileqq：同理，保护账号。
     * 这些 App 仍可"打开"（纯启动 Intent，无异能操作），但禁止 App 内搜索/发消息等 UI 自动化。
     */
    private val BLOCKED_PKGS = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
    )

    private fun isBlockedForAutomation(pkg: String): Boolean = pkg in BLOCKED_PKGS

    /**
     * code99: 自适应包名解析 —— 不再要求每装个新 App 就改 APP_ALIAS_MAP。
     * 解析顺序：
     *   1) APP_ALIAS_MAP 精确命中（QQ/微信/抖音等常用 App 走快速路径）
     *   2) 模糊包含命中（map key 与 appName 互相包含）
     *   3) PackageManager 查所有已安装的可启动 App，按显示名(label)匹配：
     *      - 精确匹配（忽略大小写）
     *      - label 包含 appName（取最短 label，避免 "QQ" 误中 "QQ音乐"）
     *   4) 都没命中返回 null（由调用方决定走浏览器搜索或报错）
     */
    private fun resolvePackage(ctx: Context, appName: String): String? {
        val name = appName.trim()
        if (name.isEmpty()) return null
        // 1) 已知映射：精确
        APP_ALIAS_MAP[name]?.let { return it }
        // 2) 已知映射：模糊包含
        APP_ALIAS_MAP.entries.firstOrNull { it.key.contains(name) || name.contains(it.key) }?.let { return it.value }
        // 3) PackageManager 动态匹配已安装 App
        return runCatching {
            val pm = ctx.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(mainIntent, 0)
            // 精确匹配（忽略大小写）
            apps.firstOrNull { it.loadLabel(pm).toString().equals(name, ignoreCase = true) }
                ?.activityInfo?.packageName
                ?: apps
                    .filter {
                        val label = it.loadLabel(pm).toString()
                        label.contains(name, ignoreCase = true)
                    }
                    // 取 label 最短的（最精确匹配，避免 "QQ" 误命中 "QQ音乐/QQ浏览器"）
                    .minByOrNull { it.loadLabel(pm).toString().length }
                    ?.activityInfo?.packageName
        }.getOrNull()
    }

    /**
     * code99: 列出已安装 App 里名字包含 keyword 的前 N 个（用于"不认识的App"报错时给提示）。
     */
    private fun suggestInstalledApps(ctx: Context, keyword: String, limit: Int = 5): List<String> = runCatching {
        val pm = ctx.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(mainIntent, 0)
            .map { it.loadLabel(pm).toString() }
            .filter { it.contains(keyword, ignoreCase = true) }
            .distinct()
            .take(limit)
    }.getOrDefault(emptyList())


    /**
     * 解析 search / open_app 参数，支持多种分隔符。
     * 返回 (pkgOrAppName, keyword)：
     *   - 若第一个 token 是已知应用名 → pkg 为真实包名；
     *   - 否则 pkg 为原始第一段文本（可能是包名 com.xxx，也可能不是）。
     *   - keyword 为空表示没有关键词。
     *
     * 支持的分隔符：空格 / 竖线 | / "搜索"关键词
     *   "抖音 斗罗大陆"      → (com.ss.android.ugc.aweme, 斗罗大陆)
     *   "抖音|斗罗大陆"      → (com.ss.android.ugc.aweme, 斗罗大陆)
     *   "抖音搜索斗罗大陆"    → (com.ss.android.ugc.aweme, 斗罗大陆)
     *   "com.xxx|关键词"     → (com.xxx, 关键词)
     *   "斗罗大陆"           → (null, 斗罗大陆)   // 没识别出应用，search 走浏览器
     *   "淘宝"               → (com.taobao.taobao, "")
     */
    private fun parseSearchArg(ctx: Context, arg: String): Pair<String?, String> {
        val trimmed = arg.trim()
        if (trimmed.isEmpty()) return null to ""

        var appPart: String? = null
        var keyword = ""

        // 1) "搜索"关键词分隔（用户口语：抖音搜索斗罗大陆）
        val searchIdx = trimmed.indexOf("搜索")
        if (searchIdx > 0) {
            appPart = trimmed.substring(0, searchIdx).trim()
            keyword = trimmed.substring(searchIdx + 2).trim()
        }
        // 2) 竖线分隔（open_app "com.xxx|关键词"）
        else if (trimmed.contains("|")) {
            val idx = trimmed.indexOf('|')
            appPart = trimmed.substring(0, idx).trim()
            keyword = trimmed.substring(idx + 1).trim()
        }
        // 3) 空格分隔（最多2段）
        else {
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            appPart = parts[0]
            keyword = parts.getOrNull(1)?.trim() ?: ""
        }

        // code99: 中文名 → 包名（自适应：已知映射 + PackageManager label 匹配）
        val resolved = resolvePackage(ctx, appPart)
        val pkg = resolved ?: appPart

        // 对于 search：如果 appPart 既不是已知应用、也没被 PackageManager 匹配到、
        //   且不是包名样式，整段当关键词走浏览器。
        val looksLikePkg = resolved != null || PKG_STYLE_RE.matches(pkg)
        if (!looksLikePkg) {
            return null to trimmed
        }
        return pkg to keyword
    }
}

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
        val cleaned = ACTION_REGEX.replace(fullText) { mr ->
            val raw = mr.value
            val namePart = mr.groupValues.getOrNull(1)?.trim()?.lowercase() ?: return@replace ""
            val argPart = mr.groupValues.getOrNull(2)?.trim() ?: ""
            // 去掉引号（如果参数被 "..." 或 '...' 包裹）
            val argument = when {
                argPart.length >= 2 && argPart.first() == '"' && argPart.last() == '"' ->
                    argPart.substring(1, argPart.length - 1)
                argPart.length >= 2 && argPart.first() == '\'' && argPart.last() == '\'' ->
                    argPart.substring(1, argPart.length - 1)
                else -> argPart
            }
            if (namePart.isNotBlank() && namePart in WHITE_LIST) {
                actions.add(ActionTag(name = namePart, argument = argument, raw = raw))
            }
            ""  // 把 ACTION 标签从正文里擦掉
        }
        return cleaned.trimEnd() to actions
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
                    "vibrate_once" -> runOnMainSync(mainHandler) {
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
        "set_brightness_high" to "调亮"
    )

    fun friendlyName(name: String): String = FRIENDLY_NAMES[name] ?: name

    // ------------------------------------------------------------------
    //  private
    // ------------------------------------------------------------------

    private val WHITE_LIST = setOf(
        "copy_to_clipboard", "open_app", "open_browser", "open_url",
        "share", "show_toast", "vibrate_once", "take_screenshot",
        "set_brightness_low", "set_brightness_high"
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
                val pkg = arg.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("open_app 需要参数=包名")
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(launch)
                } else {
                    // 🔴 用户反馈：找不到包时跳应用商店很"怪"（明明让打开微信，结果去了应用商店）
                    //     → 改成直接 Toast 未安装，不要跳转。
                    //     常见 4 个例外给出友好中文名。
                    val alias = mapOf(
                        "com.tencent.mm" to "微信",
                        "com.tencent.mobileqq" to "QQ",
                        "com.eg.android.AlipayGphone" to "支付宝",
                        "com.ss.android.ugc.aweme" to "抖音",
                    )[pkg] ?: pkg
                    throw IllegalStateException("未安装【$alias】(包名 $pkg)，请先安装后再试")
                }
            }

            "open_browser", "open_url" -> {
                val url = arg.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: throw IllegalArgumentException("$name 参数必须是 http(s) URL")
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(Intent.createChooser(i, "打开链接"))
            }

            "share" -> {
                val text = arg.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("share 需要参数=要分享的文本")
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(send, "分享"))
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

            "take_screenshot" -> {
                // 截图需要 MediaProjection (需要用户授权 + 前台服务 + ImageReader)，留到 M6 正式做。
                // 现在给个 Toast 提示，不抛异常。
                Toast.makeText(ctx, "截图功能在接入真模型后(M6)实现", Toast.LENGTH_SHORT).show()
            }

            else -> {
                throw IllegalArgumentException("未知 ACTION 名: $name")
            }
        }
    }
}

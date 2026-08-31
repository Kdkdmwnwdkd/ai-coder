package com.xuedi.coder.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import com.xuedi.coder.data.ActionTag

/**
 * 【新 M4 = 管理层】ACTION 标签解析器 + 执行器（纯 Kotlin 正则 + Android 平台 API，零额外依赖）。
 *
 * 支持的白名单（对应 PluginManager.ACTION_RULE 里的描述）：
 *   · copy_to_clipboard "要复制的文本"        → ClipboardManager
 *   · open_app "com.xxx.package"              → getLaunchIntentForPackage 或跳转应用信息页
 *   · open_browser "https://..."              → ACTION_VIEW + Intent.createChooser
 *   · show_toast "提示文字"                   → Toast
 *   · vibrate_once                            → Vibrator 200ms
 *   · take_screenshot                         → (暂无，需要 MediaProjection；TODO:M5接入)
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
     * @return Pair(成功数量, 第一个失败的描述 or null)
     */
    fun executeAll(ctx: Context, actions: List<ActionTag>): Pair<Int, String?> {
        var ok = 0
        var firstError: String? = null
        actions.forEach { a ->
            val res = runCatching { executeOne(ctx, a.name, a.argument) }
            if (res.isSuccess) ok++
            else if (firstError == null) {
                firstError = "${a.name}: ${res.exceptionOrNull()?.message ?: "执行失败"}"
            }
        }
        return ok to firstError
    }

    // ------------------------------------------------------------------
    //  private
    // ------------------------------------------------------------------

    private val WHITE_LIST = setOf(
        "copy_to_clipboard", "open_app", "open_browser",
        "show_toast", "vibrate_once", "take_screenshot",
        "set_brightness_low", "set_brightness_high"
    )

    // 正则：<ACTION...> ... </ACTION> 没有（我们是单标签），所以：
    // <ACTION\s*:\s*(\S+)(?:\s+"((?:[^"\\]|\\.)*)"|(?:\s+(\S+)))?\s*>
    // 为了更宽松，我们允许参数用引号或不用引号。
    private val ACTION_REGEX = Regex(
        pattern = """<\s*ACTION\s*:\s*([^\s>]+)(?:\s+("[^"]*"|'[^']*'|\S+))?\s*>""",
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
                    // 找不到启动页 → 跳系统应用信息页（让用户手动开）
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(i) }
                        .onFailure { throw IllegalStateException("未安装包 $pkg，也无法跳转应用信息页") }
                }
            }

            "open_browser" -> {
                val url = arg.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: throw IllegalArgumentException("open_browser 参数必须是 http(s) URL")
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(Intent.createChooser(i, "打开链接"))
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

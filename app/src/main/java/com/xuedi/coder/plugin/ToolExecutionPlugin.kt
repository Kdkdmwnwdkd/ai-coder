package com.xuedi.coder.plugin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.xuedi.coder.model.ChatPlugin
import org.json.JSONObject

/**
 * 🎯 模式 1：AI 执行模式（工具调用，纯 Kotlin，零 C++ 改动）。
 *
 * 设计要点（与 code69 底盘兼容，绝不破坏推理稳定）：
 *   · **不在流式 token 里执行**。JSON 动作提取在 ChatViewModel 接收到 ChatChunk.Done 时（一整段回复完成）才跑一次。
 *     好处：① JSON 完整（不会被 token 切片截断解析失败）② 不会每条 token 扫正则（性能好）③ 异常不会影响流式显示。
 *   · onPreSend / onPostReceive 都是空实现（ChatPlugin 接口保留，便于 PluginManager 统一管理 + 后续若要流式增强）。
 *   · 每个动作都用 runCatching 兜底；失败不抛异常，只写一条 warn 日志 + 返回"❌ ..."说明。
 *
 * 系统提示词里给模型的 JSON 白名单（见 PluginManager.TOOL_USE_SECTION）：
 *   1. open_app      {"action":"open_app","package":"com.tencent.mm"}
 *   2. open_settings {"action":"open_settings"}
 *   3. copy_text     {"action":"copy_text","text":"要复制的代码或文字"}
 *   4. set_brightness {"action":"set_brightness","level":0-255}
 *                      （没 WRITE_SETTINGS 权限时跳系统显示设置页，不崩）
 *
 * 注册：App.onCreate → LlamaJniEngine.plugins += ToolExecutionPlugin(app)
 * 调用点：ChatViewModel Done 分支 → extractExecute(ctx, cleaned) → 返回 (剥离JSON后的正文, 执行结果说明)
 */
class ToolExecutionPlugin(private val ctx: Context) : ChatPlugin {

    override fun displayName(): String = "AI执行模式"

    companion object {
        private const val TAG = "ToolExecutionPlugin"

        /**
         * Done-time JSON 动作提取 + 执行。
         * @return Pair(移除了 JSON 块的干净正文, 需要追加给用户看的执行说明；为空则啥都不追加)
         */
        fun extractExecute(context: Context, fullText: String): Pair<String, String> {
            // 只识别 "action" 字段 + 无嵌套对象（扁平参数），避免误伤代码块里的 { } 片段。
            val regex = Regex("""\{\s*"action"\s*:\s*"([^"]+)"[^}]*\}""")
            val notes = mutableListOf<String>()
            val cleaned = regex.replace(fullText) { mr ->
                val rawJson = mr.value
                val note = runCatching {
                    val json = JSONObject(rawJson)
                    val action = json.getString("action")
                    executeOne(context, action, json)
                }.getOrElse { t ->
                    Log.w(TAG, "JSON action 解析/执行失败: raw=$rawJson err=${t.message}")
                    "❌ 执行失败（${t.message ?: "未知错误"}）"
                }
                if (note.isNotBlank()) notes.add(note)
                ""  // 把 JSON 块从正文里擦掉
            }
            // 压平可能多出来的连续空行（JSON 擦掉后留下 2+ 换行）
            val flat = cleaned.replace(Regex("\n{3,}"), "\n\n").trim()
            val noteStr = notes.joinToString("\n") { "🔧 $it" }
            return flat to noteStr
        }

        // ------------------------------------------------------------
        //  单个动作执行；全部 runCatching + Toast 级结果返回给用户展示
        // ------------------------------------------------------------
        private fun executeOne(ctx: Context, action: String, json: JSONObject): String {
            return when (action) {
                "open_app" -> {
                    val pkg = runCatching { json.getString("package") }.getOrNull()
                        ?: return "❌ open_app 缺少 package 参数"
                    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(launch)
                        "✅ 正在打开 $pkg"
                    } else {
                        Toast.makeText(ctx, "未安装 $pkg，跳转应用商店", Toast.LENGTH_SHORT).show()
                        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val ok = runCatching { ctx.startActivity(market) }.isSuccess
                        if (!ok) {
                            val web = Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { ctx.startActivity(Intent.createChooser(web, "安装 $pkg")) }
                        }
                        "⚠️ 未安装 $pkg（已跳转应用商店）"
                    }
                }

                "open_settings" -> {
                    val i = Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    "✅ 正在打开系统设置"
                }

                "copy_text" -> {
                    val text = runCatching { json.getString("text") }.getOrNull()
                        ?: return "❌ copy_text 缺少 text 参数"
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ai_execution", text))
                    // Android 13+ 系统自弹"已复制"Toast，避免重复
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    val preview = text.take(30).replace("\n", " ") +
                            if (text.length > 30) "…（共${text.length}字）" else ""
                    "✅ 已复制到剪贴板：$preview"
                }

                "set_brightness" -> {
                    val level = runCatching { json.getInt("level") }.getOrNull()
                        ?: return "❌ set_brightness 缺少 level 参数 (0-255)"
                    val safeLevel = level.coerceIn(0, 255)
                    // 尝试直接写（需要用户已在系统里给本 App 打开"修改系统设置"权限）
                    val directOk = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (!Settings.System.canWrite(ctx)) {
                                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { ctx.startActivity(intent) }
                                return@runCatching false
                            }
                        }
                        Settings.System.putInt(ctx.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS, safeLevel)
                        true
                    }.getOrDefault(false)
                    if (directOk) {
                        "✅ 屏幕亮度已调整为 $safeLevel/255"
                    } else {
                        // 没权限 → 跳转亮度设置页兜底（魅族/小米 ROM 也一定能跳转）
                        runCatching {
                            val i = Intent(Settings.ACTION_DISPLAY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(i)
                        }
                        "🔧 需要「修改系统设置」权限 → 已跳转显示设置页（请手动调整为 ${safeLevel}/255）"
                    }
                }

                else -> "❌ 不支持的 action：$action"
            }
        }
    }
}

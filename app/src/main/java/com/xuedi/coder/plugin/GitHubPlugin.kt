package com.xuedi.coder.plugin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.xuedi.coder.model.ChatPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import android.os.Build as AndroidBuild

/**
 * 【code78 新增】GitHub Actions 插件 —— 在手机上直接触发编译、下载 APK、看 Actions 状态。
 *
 * 用法（ChatViewModel 里 @github 触发，或自然语言命中关键词）：
 *   @github 触发编译            → 触发 build.yml 的 workflow_dispatch（dev 分支）
 *   @github 看状态 / 看编译进度   → 拉最新 run 的状态 + 耗时 + 结论
 *   @github 下载APK / 下载最新包   → 下载最新成功 run 的 artifact 到 /Download/
 *   @github 最新commit           → 显示 HEAD sha + message
 *   @github 最近 runs            → 列出最近 5 次 workflow run
 *   @github 提交 <路径> + 代码     → Contents API 直推 dev 分支，push 自动触发构建（code100）
 *   @github                      → 不带指令 → 自动走 "看状态" 作为默认
 *
 * 依赖：GitHub Personal Access Token（需要 repo + workflow + actions:read 权限），
 *       用户在设置页填一次，存 GitHubTokenStore。
 */
class GitHubPlugin(
    private val scope: CoroutineScope,
    private val ctx: Context,
    private val tokenStore: GitHubTokenStore,
    private val resultCallback: (String) -> Unit,
) : ChatPlugin {

    fun name(): String = "GitHub 编译"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val authHeader: String get() = "Bearer ${tokenStore.token}"
    private val baseApi: String get() = "https://api.github.com/repos/${tokenStore.owner}/${tokenStore.repo}"

    private val trigger = Regex("^@github\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

    // ---------- ChatPlugin 接口 ----------

    override fun onPreSend(input: String): String {
        val m = trigger.matchEntire(input.trim()) ?: return input
        val cmd = m.groupValues[1].trim()
        // code94: 没配置 token 时，直接返回空（跳过 LLM），并通过回调提示用户去设置页填 token。
        //   之前是原样透传 → LLM 把 "@github 看状态" 当成搜索词 → 跳浏览器。
        if (!tokenStore.isConfigured()) {
            scope.launch(Dispatchers.Main.immediate) {
                runCatching {
                    resultCallback("⚠️ GitHub Token 未配置\n\n请在「设置 → GitHub 编译」填写：\n1. Owner（仓库所有者）\n2. Repo（仓库名）\n3. Workflow ID（如 build.yml）\n4. Personal Access Token（需 repo + workflow 权限）")
                }
            }
            return ""
        }
        // code97: 校验 token 格式（ghp_/gho_/ghu_/ghs_/ghr_ 开头，长度≥40）。
        //   如果过滤后 token 不对（太短或前缀错误），提前提示用户重新填。
        val t = tokenStore.token
        val validPrefix = t.startsWith("ghp_") || t.startsWith("gho_") || t.startsWith("ghu_") ||
                t.startsWith("ghs_") || t.startsWith("ghr_")
        if (t.length < 40 || !validPrefix) {
            scope.launch(Dispatchers.Main.immediate) {
                runCatching {
                    resultCallback("⚠️ GitHub Token 格式异常\n\n当前 token 长度=${t.length}，前缀=${t.take(4)}\nGitHub Token 应为 ghp_/gho_/ghu_/ghs_/ghr_ 开头的 40 位字符串。\n\n请在「设置 → GitHub 编译」重新粘贴 Token（不要带多余字符）。")
                }
            }
            return ""
        }
        // 立刻 return 空字符串（非阻塞），让 chatFlow 跳过 LLM 推理；
        // 后台协程异步调 GitHub API，结果通过 resultCallback 回传到助手消息。
        scope.launch(Dispatchers.IO + SupervisorJob()) {
            val result = withTimeoutOrNull(60_000L) {
                runCatching { executeCommand(cmd.ifBlank { "status" }) }
                    .getOrElse { "❌ 执行失败：${it.message}" }
            } ?: "❌ GitHub API 请求超时（60s），检查网络或 token 权限"
            if (result.isNotBlank()) {
                withContext(Dispatchers.Main.immediate) {
                    runCatching { resultCallback(result) }
                }
            }
        }
        return ""
    }

    override fun onPostReceive(piece: String): String = piece

    // ---------- 指令路由 ----------

    private suspend fun executeCommand(cmd: String): String = withContext(Dispatchers.IO) {
        val c = cmd.lowercase()
        when {
            // code100: 提交代码 → 走 Contents API 推送到 dev 分支（push 自动触发构建）
            // 必须在 "commit/提交" 之前判断，否则会被 fetchLatestCommit 抢走
            c.contains("上传") || c.contains("push代码") ||
                (c.contains("提交") && looksLikeFileCommit(cmd)) -> commitFile(cmd)
            c.contains("触发") || c.contains("编译") || c.contains("build") || c.contains("run") -> triggerWorkflow()
            c.contains("下载") || c.contains("apk") || c.contains("artifact") -> downloadLatestApk()
            c.contains("commit") || c.contains("提交") -> fetchLatestCommit()
            c.contains("list") || c.contains("最近") || c.contains("runs") -> listRecentRuns()
            c.contains("状态") || c.contains("进度") || c.contains("status") -> fetchLatestRunStatus()
            else -> fetchLatestRunStatus()  // 默认看状态
        }
    }

    /** 判断"提交"后面跟的是不是文件路径（含 / 且有代码文件后缀），区分于"@github 最新提交" */
    private fun looksLikeFileCommit(cmd: String): Boolean {
        val firstLine = cmd.lines().firstOrNull() ?: return false
        return firstLine.contains("/") &&
            Regex("""\.(kt|java|xml|kts|gradle|yml|yaml|json|md|txt|properties|cpp|h|cmake)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(firstLine)
    }

    // ---------- GitHub API 实现 ----------

    /** POST /actions/workflows/{id}/dispatches —— code100: ref 改为 dev（main 停在 code62，活跃开发在 dev） */
    private suspend fun triggerWorkflow(): String {
        sleepHuman(400, 300)  // 🧑‍💻 触发编译前抖一下，避免 GitHub 判自动化
        val body = JSONObject().apply {
            put("ref", "dev")
            put("inputs", JSONObject().put("triggered_by", "AI编手机助手"))
        }.toString().toRequestBody()
        val url = "$baseApi/actions/workflows/${tokenStore.workflowId}/dispatches"
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", authHeader)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 204) {
                android.util.Log.i("GitHubPlugin", "✅ workflow_dispatch 已触发")
                return "✅ 已触发 ${tokenStore.workflowId} 编译，等 1-2 分钟后 @github 下载APK 或 @github 看状态"
            }
            val err = resp.body?.string()?.take(300) ?: "HTTP ${resp.code}"
            android.util.Log.w("GitHubPlugin", "❌ trigger 失败: $err")
            return "❌ 触发失败：HTTP ${resp.code}\n$err"
        }
    }

    /** GET /actions/runs → 最新一次的状态 */
    private suspend fun fetchLatestRunStatus(): String {
        sleepHuman(200, 180)
        val url = "$baseApi/actions/runs?per_page=1"
        val req = Request.Builder().url(url).get()
            .header("Authorization", authHeader)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string()?.take(400) ?: ""
                android.util.Log.w("GitHubPlugin", "❌ status 失败: HTTP ${resp.code} url=$url body=$body")
                val hint = when (resp.code) {
                    401 -> "\n\n❌ Token 无效或已过期！\n请去 GitHub → Settings → Developer settings → Personal access tokens 重新生成一个（勾 repo + workflow 权限）"
                    403 -> "\n\n❌ Token 权限不足！需要勾选 repo 和 workflow 权限"
                    404 -> "\n\n请检查 Owner/Repo 是否正确（${tokenStore.owner}/${tokenStore.repo}）"
                    else -> ""
                }
                return "❌ API 失败：HTTP ${resp.code}\nURL: $url\n响应: $body$hint"
            }
            val j = JSONObject(resp.body?.string() ?: "{}")
            val runs = j.optJSONArray("workflow_runs") ?: return "ℹ️ 没找到 workflow run（可能仓库还没触发过 Actions）"
            if (runs.length() == 0) return "ℹ️ 仓库还没跑过 Actions"
            val run = runs.getJSONObject(0)
            val name = run.optString("name", "workflow")
            val status = run.optString("status")           // queued/in_progress/completed
            val conclusion = run.optString("conclusion") // success/failure/cancelled
            val sha = run.optString("head_sha").take(7)
            val url = run.optString("html_url")
            val created = run.optString("created_at")
            val color = when {
                conclusion == "success" -> "✅"
                conclusion == "failure" -> "❌"
                conclusion == "cancelled" -> "🚫"
                status == "in_progress" -> "⏳ 编译中…"
                status == "queued" -> "⏸ 排队中"
                else -> "❓"
            }
            val tail = if (status == "completed") conclusion.uppercase() else status
            return """📦 ${tokenStore.owner}/${tokenStore.repo} · ${run.optInt("run_number")}
  $color $name → $tail
  SHA: $sha
  时间: $created
  👉 $url"""
        }
    }

    /** 列出最近 5 次 runs */
    private suspend fun listRecentRuns(): String {
        sleepHuman(180, 150)
        val url = "$baseApi/actions/runs?per_page=5"
        val req = Request.Builder().url(url).get()
            .header("Authorization", authHeader)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return "❌ API 失败：HTTP ${resp.code}"
            val j = JSONObject(resp.body?.string() ?: "{}")
            val runs = j.optJSONArray("workflow_runs") ?: return "ℹ️ 空"
            val sb = StringBuilder("📋 最近 ${runs.length()} 次 Actions\n")
            for (i in 0 until runs.length()) {
                val r = runs.getJSONObject(i)
                val n = r.optInt("run_number")
                val s = r.optString("status")
                val c = r.optString("conclusion")
                val name = r.optString("name")
                val emoji = when {
                    c == "success" -> "✅"
                    c == "failure" -> "❌"
                    s == "in_progress" -> "⏳"
                    s == "queued" -> "⏸"
                    else -> "❓"
                }
                val tail = if (s == "completed") c.uppercase() else s
                sb.appendLine("  $emoji #$n [$tail] $name")
            }
            return sb.toString()
        }
    }

    /** 下载最新成功 run 的 APK artifact 到手机 Download/目录 */
    private suspend fun downloadLatestApk(): String {
        sleepHuman(600, 500)  // 🧑‍💻 下载前等待 run 有足够时间产出 artifacts
        // 1. 找最新成功的 run
        val runs = "$baseApi/actions/runs?per_page=20"
        val req1 = Request.Builder().url(runs).get()
            .header("Authorization", authHeader).header("Accept", "application/vnd.github+json").build()
        val latestSuccess = http.newCall(req1).execute().use { r ->
            if (!r.isSuccessful) return "❌ 找 run 失败：HTTP ${r.code}"
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("workflow_runs")
                ?: return "ℹ️ 仓库没有 Actions 记录"
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("status") == "completed" && o.optString("conclusion") == "success") {
                    // code100修复: 之前漏了 return@use，id 被丢弃导致永远走 -1L「没找到成功的run」
                    return@use o.optLong("id")
                }
            }
            -1L
        }
        if (latestSuccess < 0) return "ℹ️ 没找到任何成功的 Actions run"

        // 2. 拿这个 run 的 artifacts
        val arts = "$baseApi/actions/runs/$latestSuccess/artifacts"
        val req2 = Request.Builder().url(arts).get()
            .header("Authorization", authHeader).header("Accept", "application/vnd.github+json").build()
        val artifactId = http.newCall(req2).execute().use { r ->
            if (!r.isSuccessful) return "❌ 拿 artifact 列表失败：HTTP ${r.code}"
            val arr = JSONObject(r.body?.string() ?: "{}").optJSONArray("artifacts")
                ?: return "ℹ️ 这个 run 没有 artifact"
            // 优先找 .apk 的那个
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name").endsWith(".apk")) return@use o.optLong("id")
            }
            // 没有直接叫 apk 的就取第一个
            if (arr.length() > 0) arr.getJSONObject(0).optLong("id") else -1
        }
        if (artifactId < 0) return "ℹ️ 没找到 APK artifact"

        // 3. 下载 artifact zip → 解压 → 找 .apk
        val downloadUrl = "$baseApi/actions/artifacts/$artifactId/zip"
        val req3 = Request.Builder().url(downloadUrl).get()
            .header("Authorization", authHeader).header("Accept", "application/vnd.github+json").build()

        // 用临时下载文件
        val tmpZip = File(ctx.cacheDir, "gh_artifact_${System.currentTimeMillis()}.zip")
        val apkOutDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: File(ctx.cacheDir, "downloads")
        apkOutDir.mkdirs()

        try {
            android.util.Log.i("GitHubPlugin", "📥 开始下载 artifact zip → ${tmpZip.absolutePath}")
            http.newCall(req3).execute().use { resp ->
                if (!resp.isSuccessful) return "❌ 下载失败：HTTP ${resp.code}"
                resp.body?.byteStream()?.use { stream ->
                    FileOutputStream(tmpZip).use { out -> stream.copyTo(out) }
                }
            }
            android.util.Log.i("GitHubPlugin", "✅ zip 下载完成 (${tmpZip.length()} B)，开始解压…")

            // 解压 zip
            java.util.zip.ZipFile(tmpZip).use { zip ->
                val entries = zip.entries().toList()
                // 找 .apk
                val apkEntry = entries.firstOrNull { it.name.endsWith(".apk") }
                    ?: entries.firstOrNull { it.name.endsWith(".APK") }
                if (apkEntry == null) {
                    android.util.Log.w("GitHubPlugin", "zip 里没 .apk，entries=${entries.map { it.name }}")
                    return "❌ artifact zip 里没找到 .apk（包含：${entries.take(3).map { it.name }}）"
                }
                val outFile = File(apkOutDir, "AI编程助手-${System.currentTimeMillis()}.apk")
                zip.getInputStream(apkEntry).use { `in` ->
                    FileOutputStream(outFile).use { out -> `in`.copyTo(out) }
                }
                android.util.Log.i("GitHubPlugin", "✅ APK 已保存到 ${outFile.absolutePath} (${outFile.length()} B)")
                notifyDownloadComplete(outFile)
                return "✅ APK 已下载到：\n${outFile.absolutePath}\n大小：${outFile.length() / 1024 / 1024}MB\n👉 用文件管理器打开 /Download/ 安装"
            }
        } catch (t: Throwable) {
            android.util.Log.e("GitHubPlugin", "下载/解压失败", t)
            return "❌ 下载失败：${t.message}"
        } finally {
            tmpZip.delete()
        }
    }

    private suspend fun fetchLatestCommit(): String {
        sleepHuman(150, 130)
        val url = "$baseApi/commits?per_page=1"
        val req = Request.Builder().url(url).get()
            .header("Authorization", authHeader).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return "❌ API 失败：HTTP ${resp.code}"
            val arr = org.json.JSONArray(resp.body?.string() ?: "[]")
            if (arr.length() == 0) return "ℹ️ 仓库还没有 commit"
            val c = arr.getJSONObject(0)
            val sha = c.optString("sha").take(7)
            val msg = c.optJSONObject("commit")?.optString("message")?.take(120) ?: ""
            val author = c.optJSONObject("commit")?.optJSONObject("author")?.optString("name") ?: ""
            val date = c.optJSONObject("commit")?.optString("date") ?: ""
            return """📝 HEAD ${tokenStore.owner}/${tokenStore.repo}
  SHA: $sha
  作者: $author
  时间: $date
  $msg"""
        }
    }

    // ---------- 通知 / Toast ----------

    /**
     * 【code100 新增】提交代码到 dev 分支 —— GitHub Contents API，无需本地 git。
     *
     * 用法（第一行路径，后面跟完整文件内容，支持 ``` 围栏）：
     *   @github 提交 app/src/main/java/com/xuedi/coder/plugin/GitHubPlugin.kt
     *   ```kotlin
     *   package com.xuedi.coder.plugin
     *   ...
     *   ```
     *
     * 流程：GET 拿当前 sha（存在则需带 sha 更新）→ PUT 上传 base64 内容。
     * dev 分支有 push 触发器（build.yml on.push.branches 含 dev），提交后自动构建。
     */
    private suspend fun commitFile(cmd: String): String = withContext(Dispatchers.IO) {
        // 1. 解析：第一行提取路径，剩余作为内容
        val lines = cmd.lines()
        val firstLine = lines.firstOrNull()?.trim() ?: ""
        val path = firstLine
            .replace(Regex("""^[^\s]*?(上传|提交|push代码|push)""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('`')
        if (path.isBlank() || !path.contains("/")) {
            return@withContext "❌ 没识别到文件路径\n\n格式：\n@github 提交 <仓库内路径>\n```\n<完整文件内容>\n```\n\n例：\n@github 提交 app/src/main/java/com/xuedi/coder/vm/ChatViewModel.kt"
        }
        var content = lines.drop(1).joinToString("\n").trim()
        // 剥掉 ```kotlin / ``` 围栏
        content = content.removePrefix("```").let { s ->
            val i = s.indexOf('\n')
            if (content.startsWith("```") && i >= 0) s.substring(i + 1) else s
        }.removeSuffix("```").trim()
        if (content.isBlank()) {
            return@withContext "❌ 文件内容为空\n\n路径已识别：$path\n请把完整文件内容贴在路径下面（可用 ``` 围栏）"
        }
        if (content.length > 900_000) {
            return@withContext "❌ 文件过大（${content.length / 1024}KB，限 900KB）\n大文件请分批或走电脑端提交"
        }

        sleepHuman(300, 200)
        val encPath = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }

        // 2. GET 拿当前文件 sha（更新已存在文件必须带 sha；404 说明是新文件）
        val getReq = Request.Builder()
            .url("$baseApi/contents/$encPath?ref=dev").get()
            .header("Authorization", authHeader)
            .header("Accept", "application/vnd.github+json")
            .build()
        val existingSha: String? = http.newCall(getReq).execute().use { r ->
            when {
                r.isSuccessful -> JSONObject(r.body?.string() ?: "{}").optString("sha").ifBlank { null }
                r.code == 404 -> null
                else -> return@withContext "❌ 查询文件失败：HTTP ${r.code}"
            }
        }

        // 3. PUT 上传（base64）
        val b64 = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val putBody = JSONObject().apply {
            put("message", "📱 手机提交 $path (via AI编程助手)")
            put("content", b64)
            put("branch", "dev")
            if (existingSha != null) put("sha", existingSha)
        }.toString().toRequestBody()
        val putReq = Request.Builder()
            .url("$baseApi/contents/$encPath").put(putBody)
            .header("Authorization", authHeader)
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/json")
            .build()
        http.newCall(putReq).execute().use { r ->
            if (!r.isSuccessful) {
                val err = r.body?.string()?.take(300) ?: ""
                val hint = when (r.code) {
                    409 -> "\n（409=冲突：文件在你提交前被别人改了，重发一次即可）"
                    403 -> "\n（403=token 缺 repo 写权限）"
                    422 -> "\n（422=内容有问题，可能路径不对）"
                    else -> ""
                }
                return@withContext "❌ 提交失败：HTTP ${r.code}$hint\n$err"
            }
            val j = JSONObject(r.body?.string() ?: "{}")
            val commitSha = j.optJSONObject("commit")?.optString("sha")?.take(7) ?: "?"
            val action = if (existingSha != null) "更新" else "新建"
            return@withContext """✅ 已${action}并提交到 dev 分支
  文件: $path
  大小: ${content.length / 1024}KB
  commit: $commitSha
  👉 dev 有 push 触发器，正在自动构建，几分钟后发 @github 看状态"""
        }
    }


    private fun notifyDownloadComplete(apk: File) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel("gh_download", "GitHub 下载完成", NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val path = android.net.Uri.fromFile(apk)
                setDataAndType(path, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, "gh_download")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("APK 下载完成")
                .setContentText("${apk.name} (${apk.length() / 1024 / 1024}MB)")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(6789, n)
        }
        Toast.makeText(ctx, "APK 下载完成：${apk.name}", Toast.LENGTH_LONG).show()
    }

    /** 🧑‍💻 随机 sleep —— baseMs ± jitterMs */
    private fun sleepHuman(baseMs: Long, jitterMs: Long) {
        val wait = baseMs + Random.nextLong(-jitterMs, jitterMs + 1)
        Thread.sleep(kotlin.math.max(0L, wait))
    }

}

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
 *   @github 触发编译            → 触发 build.yml 的 workflow_dispatch
 *   @github 看状态 / 看编译进度   → 拉最新 run 的状态 + 耗时 + 结论
 *   @github 下载APK / 下载最新包   → 下载最新成功 run 的 artifact 到 /Download/
 *   @github 最新commit           → 显示 HEAD sha + message
 *   @github 最近 runs            → 列出最近 5 次 workflow run
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
        // 没配置 → 直接原样透传，让 LLM 自然回应 "请先在设置页填 GitHub Token"
        if (!tokenStore.isConfigured()) return input
        // 立刻 return input（非阻塞），后台协程异步调 GitHub API
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
        return input
    }

    override fun onPostReceive(piece: String): String = piece

    // ---------- 指令路由 ----------

    private suspend fun executeCommand(cmd: String): String = withContext(Dispatchers.IO) {
        val c = cmd.lowercase()
        when {
            c.contains("触发") || c.contains("编译") || c.contains("build") || c.contains("run") -> triggerWorkflow()
            c.contains("下载") || c.contains("apk") || c.contains("artifact") -> downloadLatestApk()
            c.contains("commit") || c.contains("提交") -> fetchLatestCommit()
            c.contains("list") || c.contains("最近") || c.contains("runs") -> listRecentRuns()
            c.contains("状态") || c.contains("进度") || c.contains("status") -> fetchLatestRunStatus()
            else -> fetchLatestRunStatus()  // 默认看状态
        }
    }

    // ---------- GitHub API 实现 ----------

    /** POST /actions/workflows/{id}/dispatches */
    private suspend fun triggerWorkflow(): String {
        sleepHuman(400, 300)  // 🧑‍💻 触发编译前抖一下，避免 GitHub 判自动化
        val body = JSONObject().apply {
            put("ref", "main")
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
            if (!resp.isSuccessful) return "❌ API 失败：HTTP ${resp.code}"
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
                    o.optLong("id")
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

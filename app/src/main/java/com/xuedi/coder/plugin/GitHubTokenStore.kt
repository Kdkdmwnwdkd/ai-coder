package com.xuedi.coder.plugin

import android.content.Context
import android.content.SharedPreferences

/**
 * GitHub 插件凭证存储。
 * —— SharedPreferences 明文存 token（debug 版够用，正式版可换 EncryptedSharedPreferences）。
 * —— 在设置页让用户填一次，之后自动用。
 *
 * 存储项：
 *   token        - GitHub Personal Access Token (ghp_xxx)，权限勾 repo + workflow
 *   owner        - GitHub 用户名或组织名（比如 "shimmer-xuedi"）
 *   repo         - 仓库名（比如 "ai-coder"）
 *   workflowId   - workflow 文件名或 ID，默认 "build.yml"
 */
class GitHubTokenStore(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    var token: String
        get() = sanitizeToken(sp.getString(KEY_TOKEN, "") ?: "")
        set(v) = sp.edit().putString(KEY_TOKEN, sanitizeToken(v)).apply()

    var owner: String
        get() = (sp.getString(KEY_OWNER, "") ?: "").trim()
        set(v) = sp.edit().putString(KEY_OWNER, v.trim()).apply()

    var repo: String
        get() {
            val raw = (sp.getString(KEY_REPO, "") ?: "").trim()
            // 兼容旧数据：如果存的是 "owner/repo" 格式，只取 repo 部分
            if (raw.contains("/")) {
                val parts = raw.split("/").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    // 如果 owner 为空，顺便补上
                    if (owner.isBlank()) sp.edit().putString(KEY_OWNER, parts[0]).apply()
                    return parts[1]
                }
            }
            return raw
        }
        set(v) {
            val clean = v.trim()
            // 兼容用户填 "owner/repo" 格式：自动拆分
            if (clean.contains("/")) {
                val parts = clean.split("/").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    if (owner.isBlank()) sp.edit().putString(KEY_OWNER, parts[0]).apply()
                    sp.edit().putString(KEY_REPO, parts[1]).apply()
                    return
                }
            }
            sp.edit().putString(KEY_REPO, clean).apply()
        }

    var workflowId: String
        get() = (sp.getString(KEY_WF, "build.yml") ?: "build.yml").trim()
        set(v) = sp.edit().putString(KEY_WF, v.trim()).apply()

    /** 全部配置好了才返回 true */
    fun isConfigured(): Boolean = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    /** 便捷方法：格式化的仓库 URL */
    fun repoUrl(): String = "https://github.com/$owner/$repo"

    fun clear() = sp.edit().clear().apply()

    companion object {
        private const val SP_NAME = "github_plugin"
        private const val KEY_TOKEN = "token"
        private const val KEY_OWNER = "owner"
        private const val KEY_REPO = "repo"
        private const val KEY_WF = "workflow_id"

        /**
         * 清洗 token：GitHub token 只含 ASCII 字母数字下划线。
         * 用户从网页复制时可能混入换行、空格、全角字符、说明文字等，全部过滤掉。
         * 如果输入里包含 "ghp_/gho_/ghu_/ghs_/ghr_" 前缀，只提取从该前缀开始的 token。
         */
        private fun sanitizeToken(raw: String): String {
            // 先找 token 前缀位置，只保留从前缀开始的部分
            val prefixes = listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_")
            var startIdx = -1
            for (p in prefixes) {
                val idx = raw.indexOf(p)
                if (idx >= 0 && (startIdx < 0 || idx < startIdx)) startIdx = idx
            }
            val work = if (startIdx >= 0) raw.substring(startIdx) else raw
            // 过滤非 ASCII 字母数字下划线
            val sb = StringBuilder()
            for (c in work) {
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.') {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}

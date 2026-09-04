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
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    var owner: String
        get() = sp.getString(KEY_OWNER, "") ?: ""
        set(v) = sp.edit().putString(KEY_OWNER, v).apply()

    var repo: String
        get() = sp.getString(KEY_REPO, "") ?: ""
        set(v) = sp.edit().putString(KEY_REPO, v).apply()

    var workflowId: String
        get() = sp.getString(KEY_WF, "build.yml") ?: "build.yml"
        set(v) = sp.edit().putString(KEY_WF, v).apply()

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
    }
}

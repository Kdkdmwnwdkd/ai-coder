package com.xuedi.coder.model

import android.content.Context
import android.content.SharedPreferences

/**
 * 【code308 新增】多平台 AI API 配置存储。
 *
 * 支持 OpenAI 兼容格式（/v1/chat/completions），可接入：
 *   · 百度千帆（通过 OpenAI 兼容 endpoint）
 *   · 阿里通义
 *   · 硅基流动
 *   · 智谱 AI
 *   · DeepSeek
 *   · 任何其他 OpenAI 兼容平台
 *
 * 存储项：
 *   provider     - 提供商标识（baidu/ali/siliconflow/zhipu/deepseek/custom）
 *   baseUrl      - API 基础地址（如 https://api.openai.com/v1）
 *   apiKey       - API Key / Access Token
 *   model        - 模型名称（如 gpt-3.5-turbo / qwen-turbo / deepseek-chat）
 *   temperature  - 采样温度（0.0~2.0）
 *   maxTokens    - 最大生成 token 数
 *   enabled      - 是否启用 API 模式（替代本地推理）
 */
class ApiConfigStore(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    var provider: String
        get() = (sp.getString(KEY_PROVIDER, "") ?: "").trim()
        set(v) = sp.edit().putString(KEY_PROVIDER, v.trim()).apply()

    var baseUrl: String
        get() {
            var url = (sp.getString(KEY_BASE_URL, "") ?: "").trim()
            if (url.isNotBlank() && !url.endsWith("/")) url += "/"
            return url
        }
        set(v) {
            var url = v.trim()
            if (url.isNotBlank() && !url.endsWith("/")) url += "/"
            sp.edit().putString(KEY_BASE_URL, url).apply()
        }

    var apiKey: String
        get() = (sp.getString(KEY_API_KEY, "") ?: "").trim()
        set(v) = sp.edit().putString(KEY_API_KEY, v.trim()).apply()

    var model: String
        get() = (sp.getString(KEY_MODEL, "") ?: "").trim()
        set(v) = sp.edit().putString(KEY_MODEL, v.trim()).apply()

    var temperature: Float
        get() = sp.getFloat(KEY_TEMP, 0.7f)
        set(v) = sp.edit().putFloat(KEY_TEMP, v.coerceIn(0.0f, 2.0f)).apply()

    var maxTokens: Int
        get() = sp.getInt(KEY_MAX_TOKENS, 2048)
        set(v) = sp.edit().putInt(KEY_MAX_TOKENS, v.coerceIn(256, 8192)).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 全部配置好了才返回 true */
    fun isConfigured(): Boolean =
        apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** 获取完整请求地址 */
    fun resolveChatUrl(): String {
        val url = baseUrl.trim()
        return if (url.endsWith("/")) "${url}chat/completions" else "$url/chat/completions"
    }

    /** 根据 provider 自动补全推荐配置 */
    fun applyPreset(preset: Preset) {
        provider = preset.id
        baseUrl = preset.baseUrl
        model = preset.defaultModel
        temperature = preset.defaultTemp
    }

    fun clear() = sp.edit().clear().apply()

    /** 预设配置 */
    data class Preset(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val defaultModel: String,
        val defaultTemp: Float = 0.7f
    )

    companion object {
        private const val SP_NAME = "api_llm_config"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMP = "temperature"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_ENABLED = "enabled"

        /** 内置预设列表 */
        val PRESETS = listOf(
            Preset(
                id = "baidu",
                displayName = "百度千帆",
                baseUrl = "https://qianfan.baidubce.com/v2",
                defaultModel = "ernie-speed-128k"
            ),
            Preset(
                id = "ali",
                displayName = "阿里通义",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                defaultModel = "qwen-turbo"
            ),
            Preset(
                id = "siliconflow",
                displayName = "硅基流动",
                baseUrl = "https://api.siliconflow.cn/v1",
                defaultModel = "deepseek-ai/DeepSeek-V3"
            ),
            Preset(
                id = "zhipu",
                displayName = "智谱 AI",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4-flash"
            ),
            Preset(
                id = "deepseek",
                displayName = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat"
            ),
            Preset(
                id = "openai",
                displayName = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-3.5-turbo"
            ),
            Preset(
                id = "custom",
                displayName = "自定义",
                baseUrl = "",
                defaultModel = ""
            )
        )
    }
}

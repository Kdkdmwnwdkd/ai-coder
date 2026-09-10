package com.novelseek.ultra.service

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.novelseek.ultra.data.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class AiService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    data class ChatMessage(
        @SerializedName("role") val role: String,
        @SerializedName("content") val content: String
    )

    data class ChatCompletionRequest(
        @SerializedName("model") val model: String,
        @SerializedName("messages") val messages: List<ChatMessage>,
        @SerializedName("temperature") val temperature: Double = 0.7,
        @SerializedName("max_tokens") val maxTokens: Int = 2000
    )

    data class ChatCompletionResponse(
        @SerializedName("choices") val choices: List<Choice>? = null
    )

    data class Choice(
        @SerializedName("message") val message: ChatMessage? = null
    )

    suspend fun generate(
        settings: UserSettings,
        prompt: String,
        systemPrompt: String = "你是一位专业的小说创作助手，擅长续写、润色、扩写和提供创意灵感。"
    ): Result<String> = withContext(Dispatchers.IO) {
        if (settings.demoMode || settings.apiKey.isBlank()) {
            return@withContext Result.success(generateDemoResponse(prompt))
        }

        try {
            val messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", prompt)
            )
            val request = ChatCompletionRequest(
                model = settings.modelName,
                messages = messages
            )

            val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("${settings.apiBase}/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: return@withContext Result.failure(
                Exception("Empty response")
            )

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("API Error: ${response.code} - $responseBody")
                )
            }

            val completion = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            val content = completion.choices?.firstOrNull()?.message?.content
                ?: return@withContext Result.failure(Exception("No content in response"))

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateDemoResponse(prompt: String): String {
        return when {
            prompt.contains("续写") -> {
                "【演示模式】\n\n夜色渐深，窗外的雨声淅沥，仿佛为这段未完的故事伴奏。主人公站在窗前，望着远处模糊的灯火，心中百感交集。接下来，他决定踏上那条通往未知的道路，去追寻那个在梦中反复出现的身影……\n\n（提示：配置 API Key 后可获得真实 AI 续写内容）"
            }
            prompt.contains("润色") -> {
                "【演示模式】\n\n月光如水，静静倾泻在古老的青石板上，每一块石头都仿佛被岁月打磨得温润如玉。微风拂过，带来了远处桂花的淡淡清香，让人不禁沉醉在这宁静而美好的夜晚之中。\n\n（提示：配置 API Key 后可获得真实 AI 润色内容）"
            }
            prompt.contains("扩写") -> {
                "【演示模式】\n\n那是一个深秋的午后，阳光透过斑驳的梧桐树叶，在地上投下细碎的光影。远处的山脊上，一片火红的枫叶如同燃烧的火焰，将半边天空染成了绚烂的橙红色。一阵凉风掠过，卷起几片枯叶在空中打着旋儿，最终轻轻落在青石板铺就的小径上。\n\n他独自走在小径上，脚步轻缓而坚定。每一步踏出，都仿佛在书写着属于自己的故事。周围的空气中弥漫着泥土和落叶混合的清新气息，让人心旷神怡。\n\n（提示：配置 API Key 后可获得真实 AI 扩写内容）"
            }
            prompt.contains("灵感") -> {
                "【演示模式】\n\n灵感建议：\n\n1. 转折设计：让主角在关键时刻发现，一直以来信任的人竟是最初事件的幕后推手，信任的崩塌将成为故事高潮的引爆点。\n\n2. 环境渲染：用一场突如其来的暴雨象征主角内心的风暴，雨中的对话可以让情感更加真挚而激烈。\n\n3. 人物成长：让配角在关键时刻做出牺牲，触发主角的觉醒与蜕变，从而推动整体剧情走向更深沉的主题。\n\n（提示：配置 API Key 后可获得真实 AI 灵感内容）"
            }
            else -> {
                "【演示模式】\n\n这里是 AI 响应的演示内容。请前往「设置」页面配置您的 API Key，即可调用真实的 AI 模型生成内容。\n\n目前支持 OpenAI 兼容格式的 API，如 DeepSeek、OpenAI、智谱 AI 等。"
            }
        }
    }
}

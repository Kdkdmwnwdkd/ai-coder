package com.inkrealm.novel.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.inkrealm.novel.data.model.UserSettings
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
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    data class ChatMessage(@SerializedName("role") val role: String, @SerializedName("content") val content: String)
    data class ChatRequest(
        @SerializedName("model") val model: String,
        @SerializedName("messages") val messages: List<ChatMessage>,
        @SerializedName("temperature") val temperature: Double = 0.7,
        @SerializedName("max_tokens") val maxTokens: Int = 2000
    )
    data class ChatResponse(@SerializedName("choices") val choices: List<Choice>? = null)
    data class Choice(@SerializedName("message") val message: ChatMessage? = null)

    data class EmbedRequest(@SerializedName("model") val model: String, @SerializedName("input") val input: List<String>)
    data class EmbedResponse(@SerializedName("data") val data: List<EmbedData>? = null)
    data class EmbedData(@SerializedName("embedding") val embedding: List<Float>? = null, @SerializedName("index") val index: Int = 0)

    suspend fun generate(
        settings: UserSettings,
        prompt: String,
        systemPrompt: String = "你是一位专业的小说创作助手。"
    ): Result<String> = withContext(Dispatchers.IO) {
        if (settings.demoMode || settings.apiKey.isBlank()) {
            return@withContext Result.success(generateDemoResponse(prompt))
        }
        try {
            val messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", prompt))
            val request = ChatRequest(model = settings.modelName, messages = messages)
            val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("${settings.apiBase}/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            if (!response.isSuccessful) return@withContext Result.failure(Exception("API ${response.code}: $responseBody"))
            val completion = gson.fromJson(responseBody, ChatResponse::class.java)
            val content = completion.choices?.firstOrNull()?.message?.content ?: return@withContext Result.failure(Exception("No content"))
            Result.success(content)
        } catch (e: Exception) {
            Log.e("AiService", "generate failed", e)
            Result.failure(e)
        }
    }

    suspend fun generateEmbedding(settings: UserSettings, texts: List<String>): Result<List<List<Float>>> = withContext(Dispatchers.IO) {
        if (settings.embeddingKey.isBlank()) return@withContext Result.failure(Exception("No embedding key"))
        try {
            val request = EmbedRequest(model = settings.embeddingModel, input = texts)
            val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("${settings.embeddingUrl}/embeddings")
                .header("Authorization", "Bearer ${settings.embeddingKey}")
                .header("Content-Type", "application/json")
                .post(body)
                .build()
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))
            if (!response.isSuccessful) return@withContext Result.failure(Exception("Embed API ${response.code}: $responseBody"))
            val embed = gson.fromJson(responseBody, EmbedResponse::class.java)
            val vectors = embed.data?.sortedBy { it.index }?.map { it.embedding ?: emptyList() } ?: emptyList()
            Result.success(vectors)
        } catch (e: Exception) {
            Log.e("AiService", "embedding failed", e)
            Result.failure(e)
        }
    }

    private fun generateDemoResponse(prompt: String): String {
        return when {
            prompt.contains("续写") -> "【演示模式】\n\n他望着窗外的雨，思绪万千。接下来，一个意想不到的身影出现在门口……\n\n（配置 API Key 后获得真实 AI 续写）"
            prompt.contains("润色") -> "【演示模式】\n\n月光如水，静静倾泻在古老的青石板上……\n\n（配置 API Key 后获得真实 AI 润色）"
            prompt.contains("扩写") -> "【演示模式】\n\n深秋的午后，阳光透过斑驳的梧桐树叶……\n\n（配置 API Key 后获得真实 AI 扩写）"
            prompt.contains("灵感") -> "【演示模式】\n\n灵感建议：\n1. 让主角发现信任的人竟是幕后推手\n2. 用暴雨象征内心风暴\n3. 配角牺牲触发主角觉醒\n\n（配置 API Key 后获得真实 AI 灵感）"
            prompt.contains("摘要") -> "【演示模式】\n\n本章主要讲述了主角在关键时刻做出抉择，推动剧情进入新的阶段。"
            prompt.contains("实体") -> "【演示模式】\n\n人物：张三（主角）\n地点：长安城\n物品：神秘玉佩\n伏笔：玉佩来历不明"
            else -> "【演示模式】\n\n这是 AI 演示内容。请在设置中配置 API Key。"
        }
    }
}

package com.xuedi.coder.model

import com.xuedi.coder.data.ChatMsg
import com.xuedi.coder.data.ChatRole
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 【code308 新增】云端 AI API 推理引擎 —— 通过 OpenAI 兼容 API 调用远程大模型。
 *
 * 支持平台：百度千帆、阿里通义、硅基流动、智谱 AI、DeepSeek、OpenAI 等任何
 * OpenAI 兼容 endpoint。
 *
 * 特性：
 *   · 流式响应（SSE Server-Sent Events），token 级实时输出
 *   · 多轮对话上下文（携带历史 messages）
 *   · 支持 temperature / max_tokens 参数配置
 *   · 超时 30s（connect）+ 120s（read），防 API 慢响应卡死
 *   · 取消机制：中断 HTTP 请求，立即停止推理
 *
 * 与本地引擎对比：
 *   ┌─────────────┬──────────────────┬────────────────────┐
 *   │   特性      │   LlamaJniEngine │    ApiLlmEngine    │
 *   ├─────────────┼──────────────────┼────────────────────┤
 *   │ 推理位置    │   本地手机 CPU    │   云端服务器       │
 *   │ 速度        │   慢（prefill 慢）│   快（取决于网络） │
 *   │ 模型大小    │   受手机内存限制  │   无限制           │
 *   │ 离线使用    │   ✅ 完全离线     │   ❌ 需要网络      │
 *   │ 隐私        │   ✅ 数据不出手机 │   ❌ 数据上传云端  │
 *   │ 多轮对话    │   同本地一样      │   自动携带上下文   │
 *   └─────────────┴──────────────────┴────────────────────┘
 */
class ApiLlmEngine(
    private val config: ApiConfigStore
) : LlmEngine {

    companion object {
        private const val TAG = "ApiLlmEngine"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val SSE_MEDIA = "text/event-stream".toMediaType()
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Volatile private var currentCall: okhttp3.Call? = null

    // ============================================================
    // LlmEngine 接口实现
    // ============================================================

    /** 简单对话（不带历史，给本地引擎兼容用） */
    override fun chatFlow(system: String, user: String): Flow<ChatChunk> {
        return chatFlow(system, user, emptyList())
    }

    /** 多轮对话（携带历史上下文） */
    fun chatFlow(system: String, user: String, history: List<ChatMsg>): Flow<ChatChunk> {
        if (!config.isConfigured()) {
            android.util.Log.e(TAG, "API 引擎未配置：baseUrl=${config.baseUrl} model=${config.model}")
            return kotlinx.coroutines.flow.flowOf(
                ChatChunk.Error(
                    RuntimeException("API 未配置"),
                    "❌ API 引擎未配置\n请在「设置 → API 配置」中填写基础地址、API Key 和模型名称。"
                )
            )
        }

        return callbackFlow {
            val fullSb = StringBuilder()

            val bodyJson = buildRequestBody(system, user, history)
            val bodyStr = bodyJson.toString()

            android.util.Log.i(TAG, "API 请求 → ${config.resolveChatUrl()} model=${config.model} history=${history.size}")

            val request = Request.Builder()
                .url(config.resolveChatUrl())
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(bodyStr.toRequestBody(JSON_MEDIA))
                .build()

            val call = http.newCall(request)
            currentCall = call

            val response = try {
                call.execute()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "API 请求失败: ${e.message}")
                trySend(ChatChunk.Error(e, "❌ API 请求失败：${e.message}"))
                close()
                return@callbackFlow
            }

            if (!response.isSuccessful) {
                val errBody = response.body?.string()?.take(500) ?: ""
                val msg = "HTTP ${response.code}：${errBody}"
                android.util.Log.e(TAG, "API 响应错误: $msg")
                trySend(ChatChunk.Error(RuntimeException(msg), "❌ API 响应错误\n$msg"))
                close()
                return@callbackFlow
            }

            // 解析 SSE 流
            val reader = response.body?.charStream()
            if (reader == null) {
                trySend(ChatChunk.Error(RuntimeException("响应体为空"), "❌ API 响应体为空"))
                close()
                return@callbackFlow
            }

            try {
                reader.useLines { lines ->
                    for (line in lines) {
                        if (!call.isExecuted && call.isCanceled()) {
                            android.util.Log.i(TAG, "API 请求已取消")
                            break
                        }
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6)
                            if (data == "[DONE]") {
                                android.util.Log.i(TAG, "API 流结束 [DONE]")
                                break
                            }
                            parseSseChunk(data)?.let { token ->
                                fullSb.append(token)
                                trySend(ChatChunk.Token(token))
                            }
                        }
                    }
                }
                trySend(ChatChunk.Done(full = fullSb.toString()))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "SSE 解析异常: ${e.message}")
                trySend(ChatChunk.Error(e, "❌ 流式解析异常：${e.message}"))
            } finally {
                response.close()
                currentCall = null
                close()
            }

            awaitClose {
                currentCall?.cancel()
                currentCall = null
            }
        }
    }

    override fun cancel() {
        android.util.Log.i(TAG, "API 推理取消")
        currentCall?.cancel()
        currentCall = null
    }

    override fun release() {
        cancel()
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /** 构建 OpenAI 兼容格式的请求体 */
    private fun buildRequestBody(
        system: String,
        user: String,
        history: List<ChatMsg>
    ): JsonObject {
        val messages = JsonArray()

        // System prompt
        if (system.isNotBlank()) {
            val sys = JsonObject()
            sys.addProperty("role", "system")
            sys.addProperty("content", system)
            messages.add(sys)
        }

        // 历史消息（排除 pending 中的消息和 Error 消息）
        for (msg in history) {
            if (msg.pending || msg.role == ChatRole.Error || msg.role == ChatRole.System) continue
            val role = when (msg.role) {
                ChatRole.User -> "user"
                ChatRole.Assistant -> "assistant"
                else -> continue
            }
            val m = JsonObject()
            m.addProperty("role", role)
            m.addProperty("content", msg.content)
            messages.add(m)
        }

        // 当前用户消息
        val userMsg = JsonObject()
        userMsg.addProperty("role", "user")
        userMsg.addProperty("content", user)
        messages.add(userMsg)

        val body = JsonObject()
        body.addProperty("model", config.model)
        body.add("messages", messages)
        body.addProperty("stream", true)
        body.addProperty("temperature", config.temperature)
        body.addProperty("max_tokens", config.maxTokens)

        return body
    }

    /** 解析单个 SSE chunk，返回 token 文本或 null */
    private fun parseSseChunk(data: String): String? {
        return try {
            val obj = JsonParser.parseString(data).asJsonObject
            val choices = obj.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) return null

            val choice = choices[0].asJsonObject
            val delta = choice.getAsJsonObject("delta")
            if (delta == null) return null

            val content = delta.get("content")
            if (content == null || content.isJsonNull) return null

            content.asString
        } catch (e: Exception) {
            android.util.Log.w(TAG, "SSE chunk 解析失败: ${e.message} data=${data.take(100)}")
            null
        }
    }
}

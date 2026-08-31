package com.xuedi.coder.model

import kotlinx.coroutines.flow.Flow

/**
 * 推理引擎抽象。M6 之前先用 MockLlmEngine 跑通 UI；
 * 真机验证通过后写 LlamaJniEngine 接真 JNI llama.cpp。
 */
interface LlmEngine {
    fun chatFlow(system: String, user: String): Flow<ChatChunk>
    fun release()
}

sealed class ChatChunk {
    data class Token(val text: String) : ChatChunk()
    data class Done(val full: String, val stopReason: String = "stop") : ChatChunk()
    data class Error(val t: Throwable, val hint: String = t.message ?: "推理出错") : ChatChunk()
}

package com.xuedi.coder.model

import kotlinx.coroutines.flow.Flow

/**
 * 推理引擎抽象。M6 之前先用 MockLlmEngine 跑通 UI；
 * 真机验证通过后写 LlamaJniEngine 接真 JNI llama.cpp。
 */
interface LlmEngine {
    fun chatFlow(system: String, user: String): Flow<ChatChunk>
    /** 取消当前正在跑的推理（只停推理，不释放模型权重，后续还能继续发新问题）。 */
    fun cancel()
    /** 完全释放：取消推理 + 释放模型权重内存（进程退出或切模型前调用）。 */
    fun release()
}

sealed class ChatChunk {
    data class Token(val text: String) : ChatChunk()
    data class Done(val full: String, val stopReason: String = "stop") : ChatChunk()
    data class Error(val t: Throwable, val hint: String = t.message ?: "推理出错") : ChatChunk()
    /** 🔴 预填充进度（0~100%），UI 显示百分比避免一直白转圈圈 */
    data class PrefillProgress(val percent: Int, val consumed: Int, val total: Int) : ChatChunk()
}

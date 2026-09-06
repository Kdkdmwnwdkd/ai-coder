package com.xuedi.coder.data

import java.util.UUID

enum class ChatRole { User, Assistant, Error, System }

data class ChatMsg(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    var content: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    /** 是否仍在流式生成中 */
    var pending: Boolean = false
)

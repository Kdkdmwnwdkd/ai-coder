package com.xuedi.coder.data

import java.util.UUID

data class CodeBlock(
    val index: Int,
    val language: String,
    val code: String
)

data class ActionTag(
    val name: String,
    val argument: String,
    val raw: String
)

enum class ChatRole { User, Assistant, Error, System }

data class ChatMsg(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    var content: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    /** Assistant 消息流式结束后解析出的代码块（UI用） */
    var codeBlocks: List<CodeBlock> = emptyList(),
    /** Assistant 消息里解析出的 ACTION 标签（UI用，按钮） */
    var actions: List<ActionTag> = emptyList(),
    /** 是否仍在流式生成中 */
    var pending: Boolean = false
)

package com.xuedi.coder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 聊天消息 Room 持久化（修复 Bug：退出 APP / Flyme 后台被杀后聊天记录清空）。
 *
 * 写入策略：
 *   - 新消息 append 到 `_messages` 后立即 `chatDao.upsert(msg)`
 *   - 推理进行中每次 `content = sb.toString()` 更新时也 upsert（开销极小：单线程 IO，单条语句）
 *   - 删除/清空消息同步删表
 *
 * 读取策略：
 *   - ChatViewModel init 时 `chatDao.observeAll().first()` 读出到 `_messages`，
 *     与内置 welcome 消息合并（welcome id="welcome" 不入库、每次重新构造）。
 */
@Entity(tableName = "chat_message")
data class ChatMsgEntity(
    @PrimaryKey val id: String,
    /** 所属话题 id（外键关联 chat_topic.id，但不强制 FOREIGN KEY 约束以简化迁移） */
    val topicId: String,
    /** 0=User 1=Assistant 2=Error 3=System（对应 ChatRole ordinal） */
    val roleOrdinal: Int,
    val content: String,
    val createdAtMs: Long,
    /** pending=true 表示正在流式生成中（APP 进程被杀后恢复到 content 最终值） */
    val pending: Boolean = false,
    /** ACTION name / argument / raw 用 "|||" 分隔三个字段；多个用 ";;;;" 分隔。简单场景避免单独一张表。 */
    val actionsSerialized: String = "",
) {
    companion object {
        const val SEP_ACTIONS = ";;;;"
        const val SEP_FIELDS = "|||"

        fun from(msg: ChatMsg, topicId: String): ChatMsgEntity = ChatMsgEntity(
            id = msg.id,
            topicId = topicId,
            roleOrdinal = msg.role.ordinal,
            content = msg.content,
            createdAtMs = msg.createdAtMs,
            pending = msg.pending,
            actionsSerialized = msg.actions.joinToString(SEP_ACTIONS) { a ->
                "${a.name}$SEP_FIELDS${a.argument}$SEP_FIELDS${a.raw}"
            },
        )

        fun toMsg(e: ChatMsgEntity): ChatMsg {
            val actions = e.actionsSerialized.takeIf { it.isNotBlank() }
                ?.split(SEP_ACTIONS)
                ?.mapNotNull { seg ->
                    val parts = seg.split(SEP_FIELDS, limit = 3)
                    if (parts.size == 3) ActionTag(parts[0], parts[1], parts[2]) else null
                }
                ?: emptyList()
            val role = ChatRole.values().getOrElse(e.roleOrdinal) { ChatRole.Assistant }
            return ChatMsg(
                id = e.id,
                role = role,
                content = e.content,
                createdAtMs = e.createdAtMs,
                actions = actions,
                pending = e.pending,
            )
        }
    }
}

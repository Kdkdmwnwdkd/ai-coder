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
 * 注意：actionsSerialized 列保留（动作模式 code277 已删除，恒为空串）——
 *   不能删列：Room version=2 + fallbackToDestructiveMigration，删列要升 version，
 *   破坏性迁移会清空用户全部聊天记录。
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
    /** 历史遗留列（动作模式已删，恒为空串）。保留仅为兼容表结构，避免破坏性迁移清记录。 */
    val actionsSerialized: String = "",
) {
    companion object {
        fun from(msg: ChatMsg, topicId: String): ChatMsgEntity = ChatMsgEntity(
            id = msg.id,
            topicId = topicId,
            roleOrdinal = msg.role.ordinal,
            content = msg.content,
            createdAtMs = msg.createdAtMs,
            pending = msg.pending,
        )

        fun toMsg(e: ChatMsgEntity): ChatMsg {
            val role = ChatRole.values().getOrElse(e.roleOrdinal) { ChatRole.Assistant }
            return ChatMsg(
                id = e.id,
                role = role,
                content = e.content,
                createdAtMs = e.createdAtMs,
                pending = e.pending,
            )
        }
    }
}

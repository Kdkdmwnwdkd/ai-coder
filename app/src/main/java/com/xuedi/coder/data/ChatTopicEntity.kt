package com.xuedi.coder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 聊天话题（多会话，类似 ChatGPT 侧边栏的话题列表）。
 *
 * 一个话题包含多条 ChatMsgEntity(topicId 外键关联)。
 * - title：用户可改；默认用首条用户消息前 24 字截断。
 * - lastActiveMs：每次该话题有新消息时刷新 → 侧边栏按此排序。
 * - archived：暂未使用（预留软删除/归档功能）。
 */
@Entity(tableName = "chat_topic")
data class ChatTopicEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtMs: Long,
    val lastActiveMs: Long,
    val archived: Boolean = false
)

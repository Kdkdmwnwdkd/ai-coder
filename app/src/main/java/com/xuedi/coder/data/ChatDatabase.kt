package com.xuedi.coder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 聊天 Database：chat_topic + chat_message 两张表。
 *  v2：新增 ChatTopicEntity；ChatMsgEntity 加 topicId 字段。
 *  fallbackToDestructiveMigration：旧 v1 库（只有 chat_message 没字段）→ 重启库，
 *  旧消息会丢失（多话题功能上线时一次性的，用户已验证 v1 OK 可接受）。
 */
@Database(
    entities = [ChatMsgEntity::class, ChatTopicEntity::class],
    version = 2,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao
    abstract fun topicDao(): ChatTopicDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null
        fun get(ctx: Context): ChatDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                ChatDatabase::class.java,
                "chat_message.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

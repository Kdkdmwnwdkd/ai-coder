package com.xuedi.coder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 聊天消息独立 Database（避免干扰 ModelDatabase v1 / PluginDatabase）。
 *  fallbackToDestructiveMigration：聊天数据丢失=退回"欢迎消息"，无业务副作用。
 */
@Database(
    entities = [ChatMsgEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao

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

package com.novelseek.ultra.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.novelseek.ultra.data.dao.*
import com.novelseek.ultra.data.model.*

@Database(
    entities = [
        Work::class,
        Chapter::class,
        Character::class,
        WorldSetting::class,
        Outline::class,
        AgentConfig::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(WorkTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao
    abstract fun chapterDao(): ChapterDao
    abstract fun characterDao(): CharacterDao
    abstract fun worldSettingDao(): WorldSettingDao
    abstract fun outlineDao(): OutlineDao
    abstract fun agentConfigDao(): AgentConfigDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "novelseek_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

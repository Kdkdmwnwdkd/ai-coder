package com.inkrealm.novel.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.inkrealm.novel.data.dao.*
import com.inkrealm.novel.data.model.*

@Database(
    entities = [Work::class, Chapter::class, Arc::class, Character::class, WorldSetting::class, EntityExtract::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(WorkTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao
    abstract fun arcDao(): ArcDao
    abstract fun chapterDao(): ChapterDao
    abstract fun characterDao(): CharacterDao
    abstract fun worldSettingDao(): WorldSettingDao
    abstract fun entityDao(): EntityDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "inkrealm_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

package com.xuedi.coder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ModelEntity::class], version = 1, exportSchema = true)
abstract class ModelDatabase : RoomDatabase() {
    abstract fun dao(): ModelDao

    companion object {
        @Volatile
        private var INSTANCE: ModelDatabase? = null
        fun get(ctx: Context): ModelDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                ModelDatabase::class.java,
                "model_info.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

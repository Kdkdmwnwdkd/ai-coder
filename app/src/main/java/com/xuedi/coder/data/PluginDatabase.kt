package com.xuedi.coder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PluginEntity::class],
    version = 2,
    exportSchema = true
)
abstract class PluginDatabase : RoomDatabase() {
    abstract fun dao(): PluginDao

    companion object {
        @Volatile
        private var INSTANCE: PluginDatabase? = null
        fun get(ctx: Context): PluginDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                PluginDatabase::class.java,
                "plugin_state.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

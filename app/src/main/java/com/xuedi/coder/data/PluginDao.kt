package com.xuedi.coder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginDao {
    @Query("SELECT * FROM plugin_state ORDER BY id ASC")
    fun observeAll(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM plugin_state ORDER BY id ASC")
    suspend fun getAll(): List<PluginEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vararg entities: PluginEntity)

    @Query("UPDATE plugin_state SET enabled = :enable WHERE id = :id")
    suspend fun setEnabled(id: String, enable: Boolean)

    @Query("SELECT * FROM plugin_state WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PluginEntity?
}

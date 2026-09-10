package com.inkrealm.novel.data.dao

import androidx.room.*
import com.inkrealm.novel.data.model.WorldSetting
import kotlinx.coroutines.flow.Flow

@Dao
interface WorldSettingDao {
    @Query("SELECT * FROM world_settings WHERE workId = :workId ORDER BY createdAt DESC")
    fun getByWorkId(workId: Long): Flow<List<WorldSetting>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: WorldSetting): Long

    @Update
    suspend fun update(setting: WorldSetting)

    @Delete
    suspend fun delete(setting: WorldSetting)
}

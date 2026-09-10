package com.novelseek.ultra.data.dao

import androidx.room.*
import com.novelseek.ultra.data.model.Outline

@Dao
interface OutlineDao {
    @Query("SELECT * FROM outlines WHERE workId = :workId LIMIT 1")
    suspend fun getByWorkId(workId: Long): Outline?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outline: Outline): Long

    @Update
    suspend fun update(outline: Outline)
}

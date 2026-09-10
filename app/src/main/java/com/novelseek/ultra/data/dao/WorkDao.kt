package com.novelseek.ultra.data.dao

import androidx.room.*
import com.novelseek.ultra.data.model.Work
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkDao {
    @Query("SELECT * FROM works ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<Work>>

    @Query("SELECT * FROM works WHERE id = :id")
    suspend fun getById(id: Long): Work?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(work: Work): Long

    @Update
    suspend fun update(work: Work)

    @Delete
    suspend fun delete(work: Work)

    @Query("UPDATE works SET updatedAt = :time WHERE id = :id")
    suspend fun updateTime(id: Long, time: Long = System.currentTimeMillis())
}

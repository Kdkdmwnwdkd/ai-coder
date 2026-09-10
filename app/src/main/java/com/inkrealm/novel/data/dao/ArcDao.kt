package com.inkrealm.novel.data.dao

import androidx.room.*
import com.inkrealm.novel.data.model.Arc
import kotlinx.coroutines.flow.Flow

@Dao
interface ArcDao {
    @Query("SELECT * FROM arcs WHERE workId = :workId ORDER BY orderIndex ASC")
    fun getByWorkId(workId: Long): Flow<List<Arc>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(arc: Arc): Long

    @Update
    suspend fun update(arc: Arc)

    @Delete
    suspend fun delete(arc: Arc)
}

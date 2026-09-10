package com.inkrealm.novel.data.dao

import androidx.room.*
import com.inkrealm.novel.data.model.EntityExtract
import kotlinx.coroutines.flow.Flow

@Dao
interface EntityDao {
    @Query("SELECT * FROM entities WHERE workId = :workId AND resolved = 0 ORDER BY createdAt DESC")
    fun getUnresolved(workId: Long): Flow<List<EntityExtract>>

    @Query("SELECT * FROM entities WHERE workId = :workId AND type = :type")
    fun getByType(workId: Long, type: String): Flow<List<EntityExtract>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EntityExtract): Long

    @Update
    suspend fun update(entity: EntityExtract)

    @Delete
    suspend fun delete(entity: EntityExtract)

    @Query("DELETE FROM entities WHERE workId = :workId")
    suspend fun clearWork(workId: Long)
}

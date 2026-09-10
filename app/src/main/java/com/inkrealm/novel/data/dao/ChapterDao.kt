package com.inkrealm.novel.data.dao

import androidx.room.*
import com.inkrealm.novel.data.model.Chapter
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE workId = :workId AND arcId = :arcId ORDER BY orderIndex ASC")
    fun getByWorkAndArc(workId: Long, arcId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE workId = :workId ORDER BY orderIndex ASC")
    fun getByWorkId(workId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: Long): Chapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chapter: Chapter): Long

    @Update
    suspend fun update(chapter: Chapter)

    @Delete
    suspend fun delete(chapter: Chapter)

    @Query("SELECT MAX(orderIndex) FROM chapters WHERE workId = :workId")
    suspend fun getMaxOrder(workId: Long): Int?

    @Query("UPDATE chapters SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun updateOrder(id: Long, orderIndex: Int)

    @Query("SELECT * FROM chapters WHERE workId = :workId ORDER BY orderIndex DESC LIMIT 3")
    suspend fun getRecentChapters(workId: Long): List<Chapter>
}

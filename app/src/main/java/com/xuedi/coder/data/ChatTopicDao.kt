package com.xuedi.coder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatTopicDao {

    @Query("SELECT * FROM chat_topic WHERE archived = 0 ORDER BY lastActiveMs DESC")
    fun observeAll(): Flow<List<ChatTopicEntity>>

    @Query("SELECT * FROM chat_topic WHERE archived = 0 ORDER BY lastActiveMs DESC")
    suspend fun getAll(): List<ChatTopicEntity>

    @Query("SELECT * FROM chat_topic WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ChatTopicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(topic: ChatTopicEntity)

    @Query("UPDATE chat_topic SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE chat_topic SET lastActiveMs = :ms WHERE id = :id")
    suspend fun touchActive(id: String, ms: Long)

    @Query("DELETE FROM chat_topic WHERE id = :id")
    suspend fun deleteById(id: String)
}

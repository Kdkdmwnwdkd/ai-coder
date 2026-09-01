package com.xuedi.coder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_message ORDER BY createdAtMs ASC")
    fun observeAll(): Flow<List<ChatMsgEntity>>

    @Query("SELECT * FROM chat_message ORDER BY createdAtMs ASC")
    suspend fun getAll(): List<ChatMsgEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(msg: ChatMsgEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<ChatMsgEntity>)

    @Query("DELETE FROM chat_message WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_message")
    suspend fun deleteAll()
}

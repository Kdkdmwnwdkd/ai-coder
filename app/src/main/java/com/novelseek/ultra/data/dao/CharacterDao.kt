package com.novelseek.ultra.data.dao

import androidx.room.*
import com.novelseek.ultra.data.model.Character
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters WHERE workId = :workId ORDER BY createdAt DESC")
    fun getByWorkId(workId: Long): Flow<List<Character>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(character: Character): Long

    @Update
    suspend fun update(character: Character)

    @Delete
    suspend fun delete(character: Character)
}

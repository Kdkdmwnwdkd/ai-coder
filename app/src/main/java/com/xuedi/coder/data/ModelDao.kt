package com.xuedi.coder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM model_info ORDER BY addedAtMs DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM model_info WHERE selected = 1 LIMIT 1")
    fun observeSelected(): Flow<ModelEntity?>

    @Query("SELECT * FROM model_info WHERE selected = 1 LIMIT 1")
    suspend fun getSelected(): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Query("UPDATE model_info SET selected = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun selectOnly(id: String)

    @Query("DELETE FROM model_info WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM model_info WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ModelEntity?
}

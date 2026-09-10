package com.novelseek.ultra.data.dao

import androidx.room.*
import com.novelseek.ultra.data.model.AgentConfig

@Dao
interface AgentConfigDao {
    @Query("SELECT * FROM agent_configs WHERE workId = :workId LIMIT 1")
    suspend fun getByWorkId(workId: Long): AgentConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: AgentConfig): Long

    @Update
    suspend fun update(config: AgentConfig)
}

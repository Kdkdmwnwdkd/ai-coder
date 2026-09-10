package com.novelseek.ultra.data.repository

import com.novelseek.ultra.data.dao.AgentConfigDao
import com.novelseek.ultra.data.model.AgentConfig

class AgentConfigRepository(private val agentConfigDao: AgentConfigDao) {
    suspend fun getByWorkId(workId: Long): AgentConfig? = agentConfigDao.getByWorkId(workId)

    suspend fun save(config: AgentConfig): Long {
        return if (config.id == 0L) {
            agentConfigDao.insert(config)
        } else {
            agentConfigDao.update(config)
            config.id
        }
    }

    suspend fun getOrCreate(workId: Long): AgentConfig {
        return getByWorkId(workId) ?: AgentConfig(workId = workId).also {
            agentConfigDao.insert(it)
        }
    }
}

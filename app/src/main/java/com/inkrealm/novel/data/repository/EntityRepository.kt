package com.inkrealm.novel.data.repository

import com.inkrealm.novel.data.dao.EntityDao
import com.inkrealm.novel.data.model.EntityExtract
import kotlinx.coroutines.flow.Flow

class EntityRepository(private val entityDao: EntityDao) {
    fun getUnresolved(workId: Long): Flow<List<EntityExtract>> = entityDao.getUnresolved(workId)
    suspend fun insert(entity: EntityExtract): Long = entityDao.insert(entity)
    suspend fun update(entity: EntityExtract) = entityDao.update(entity)
    suspend fun clearWork(workId: Long) = entityDao.clearWork(workId)
}

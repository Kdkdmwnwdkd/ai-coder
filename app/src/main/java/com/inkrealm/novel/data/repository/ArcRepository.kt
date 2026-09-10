package com.inkrealm.novel.data.repository

import com.inkrealm.novel.data.dao.ArcDao
import com.inkrealm.novel.data.model.Arc
import kotlinx.coroutines.flow.Flow

class ArcRepository(private val arcDao: ArcDao) {
    fun getByWorkId(workId: Long): Flow<List<Arc>> = arcDao.getByWorkId(workId)
    suspend fun insert(arc: Arc): Long = arcDao.insert(arc)
    suspend fun update(arc: Arc) = arcDao.update(arc)
    suspend fun delete(arc: Arc) = arcDao.delete(arc)
}

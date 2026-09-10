package com.inkrealm.novel.data.repository

import com.inkrealm.novel.data.dao.WorkDao
import com.inkrealm.novel.data.model.Work
import kotlinx.coroutines.flow.Flow

class WorkRepository(private val workDao: WorkDao) {
    val allWorks: Flow<List<Work>> = workDao.getAll()
    suspend fun getById(id: Long): Work? = workDao.getById(id)
    suspend fun insert(work: Work): Long = workDao.insert(work)
    suspend fun update(work: Work) = workDao.update(work)
    suspend fun delete(work: Work) = workDao.delete(work)
    suspend fun updateTime(id: Long) = workDao.updateTime(id)
    suspend fun getChapterCount(workId: Long): Int = workDao.getChapterCount(workId)
    suspend fun getWordCount(workId: Long): Int = workDao.getWordCount(workId) ?: 0
}

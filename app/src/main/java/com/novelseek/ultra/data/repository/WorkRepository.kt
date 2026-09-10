package com.novelseek.ultra.data.repository

import com.novelseek.ultra.data.dao.WorkDao
import com.novelseek.ultra.data.model.Work
import kotlinx.coroutines.flow.Flow

class WorkRepository(private val workDao: WorkDao) {
    val allWorks: Flow<List<Work>> = workDao.getAll()

    suspend fun getById(id: Long): Work? = workDao.getById(id)

    suspend fun insert(work: Work): Long = workDao.insert(work)

    suspend fun update(work: Work) = workDao.update(work)

    suspend fun delete(work: Work) = workDao.delete(work)

    suspend fun updateTime(id: Long) = workDao.updateTime(id)
}

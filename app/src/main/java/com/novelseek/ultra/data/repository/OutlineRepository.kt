package com.novelseek.ultra.data.repository

import com.novelseek.ultra.data.dao.OutlineDao
import com.novelseek.ultra.data.model.Outline

class OutlineRepository(private val outlineDao: OutlineDao) {
    suspend fun getByWorkId(workId: Long): Outline? = outlineDao.getByWorkId(workId)

    suspend fun save(outline: Outline): Long {
        return if (outline.id == 0L) {
            outlineDao.insert(outline)
        } else {
            outlineDao.update(outline)
            outline.id
        }
    }
}

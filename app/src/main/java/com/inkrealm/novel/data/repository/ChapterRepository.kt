package com.inkrealm.novel.data.repository

import com.inkrealm.novel.data.dao.ChapterDao
import com.inkrealm.novel.data.model.Chapter
import kotlinx.coroutines.flow.Flow

class ChapterRepository(private val chapterDao: ChapterDao) {
    fun getByWorkId(workId: Long): Flow<List<Chapter>> = chapterDao.getByWorkId(workId)
    fun getByWorkAndArc(workId: Long, arcId: Long): Flow<List<Chapter>> = chapterDao.getByWorkAndArc(workId, arcId)
    suspend fun getById(id: Long): Chapter? = chapterDao.getById(id)
    suspend fun insert(chapter: Chapter): Long = chapterDao.insert(chapter)
    suspend fun update(chapter: Chapter) = chapterDao.update(chapter)
    suspend fun delete(chapter: Chapter) = chapterDao.delete(chapter)
    suspend fun getMaxOrder(workId: Long): Int = chapterDao.getMaxOrder(workId) ?: -1
    suspend fun getRecentChapters(workId: Long): List<Chapter> = chapterDao.getRecentChapters(workId)
    suspend fun addChapter(workId: Long, arcId: Long, title: String): Long {
        val maxOrder = getMaxOrder(workId)
        return chapterDao.insert(Chapter(workId = workId, arcId = arcId, title = title, orderIndex = maxOrder + 1))
    }
}

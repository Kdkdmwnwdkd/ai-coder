package com.novelseek.ultra.data.repository

import com.novelseek.ultra.data.dao.ChapterDao
import com.novelseek.ultra.data.model.Chapter
import kotlinx.coroutines.flow.Flow

class ChapterRepository(private val chapterDao: ChapterDao) {
    fun getByWorkId(workId: Long): Flow<List<Chapter>> = chapterDao.getByWorkId(workId)

    suspend fun getById(id: Long): Chapter? = chapterDao.getById(id)

    suspend fun insert(chapter: Chapter): Long = chapterDao.insert(chapter)

    suspend fun update(chapter: Chapter) = chapterDao.update(chapter)

    suspend fun delete(chapter: Chapter) = chapterDao.delete(chapter)

    suspend fun getMaxOrder(workId: Long): Int = chapterDao.getMaxOrder(workId) ?: -1

    suspend fun addChapter(workId: Long, title: String): Long {
        val maxOrder = getMaxOrder(workId)
        val chapter = Chapter(
            workId = workId,
            title = title,
            orderIndex = maxOrder + 1
        )
        return chapterDao.insert(chapter)
    }

    suspend fun reorderChapters(chapters: List<Chapter>) {
        chapters.forEachIndexed { index, chapter ->
            chapterDao.updateOrder(chapter.id, index)
        }
    }
}

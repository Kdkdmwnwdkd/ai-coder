package com.inkrealm.novel.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkrealm.novel.data.local.AppDatabase
import com.inkrealm.novel.data.model.Arc
import com.inkrealm.novel.data.model.Chapter
import com.inkrealm.novel.data.model.Work
import com.inkrealm.novel.data.repository.ArcRepository
import com.inkrealm.novel.data.repository.ChapterRepository
import com.inkrealm.novel.data.repository.WorkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class WorkDetailViewModel(application: Application, private val workId: Long) : AndroidViewModel(application) {
    private val workRepo: WorkRepository
    private val chapterRepo: ChapterRepository
    private val arcRepo: ArcRepository

    val work: Flow<Work?> get() = workRepo.allWorks.map { list -> list.find { it.id == workId } }
    val chapters: Flow<List<Chapter>>
    val arcs: Flow<List<Arc>>

    init {
        val db = AppDatabase.getDatabase(application)
        workRepo = WorkRepository(db.workDao())
        chapterRepo = ChapterRepository(db.chapterDao())
        arcRepo = ArcRepository(db.arcDao())
        chapters = chapterRepo.getByWorkId(workId)
        arcs = arcRepo.getByWorkId(workId)
    }

    fun addChapter(title: String, arcId: Long = 0) {
        viewModelScope.launch {
            try {
                chapterRepo.addChapter(workId, arcId, title)
                workRepo.updateTime(workId)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun deleteChapter(chapter: Chapter) {
        viewModelScope.launch {
            try { chapterRepo.delete(chapter) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun addArc(name: String) {
        viewModelScope.launch {
            try {
                val max = arcRepo.getByWorkId(workId).map { it.maxOfOrNull { a -> a.orderIndex } ?: -1 }
                arcRepo.insert(Arc(workId = workId, name = name, orderIndex = 0))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}

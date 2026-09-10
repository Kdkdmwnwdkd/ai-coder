package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.AppDatabase
import com.novelseek.ultra.data.model.Chapter
import com.novelseek.ultra.data.model.Work
import com.novelseek.ultra.data.repository.ChapterRepository
import com.novelseek.ultra.data.repository.WorkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class WorkDetailViewModel(application: Application, private val workId: Long) : AndroidViewModel(application) {
    private val workRepository: WorkRepository
    private val chapterRepository: ChapterRepository

    val work: Flow<Work?> get() = workRepository.allWorks.map { list -> list.find { it.id == workId } }
    val chapters: Flow<List<Chapter>>

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        val db = AppDatabase.getDatabase(application)
        workRepository = WorkRepository(db.workDao())
        chapterRepository = ChapterRepository(db.chapterDao())
        chapters = chapterRepository.getByWorkId(workId)
    }

    fun addChapter(title: String) {
        viewModelScope.launch {
            _isLoading.value = true
            chapterRepository.addChapter(workId, title)
            workRepository.updateTime(workId)
            _isLoading.value = false
        }
    }

    fun deleteChapter(chapter: Chapter) {
        viewModelScope.launch {
            chapterRepository.delete(chapter)
        }
    }

    fun updateWorkTitle(title: String, description: String) {
        viewModelScope.launch {
            val currentWork = workRepository.getById(workId)
            currentWork?.let {
                workRepository.update(it.copy(title = title, description = description, updatedAt = System.currentTimeMillis()))
            }
        }
    }
}

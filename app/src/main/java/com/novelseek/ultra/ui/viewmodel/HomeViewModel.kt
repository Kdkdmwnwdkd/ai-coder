package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.AppDatabase
import com.novelseek.ultra.data.model.Work
import com.novelseek.ultra.data.model.WorkType
import com.novelseek.ultra.data.repository.WorkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: WorkRepository
    val allWorks: Flow<List<Work>>

    init {
        val db = AppDatabase.getDatabase(application)
        repository = WorkRepository(db.workDao())
        allWorks = repository.allWorks
    }

    fun createWork(title: String, description: String, type: WorkType, onResult: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.insert(Work(title = title, description = description, type = type))
            onResult(id)
        }
    }

    fun deleteWork(work: Work) {
        viewModelScope.launch {
            repository.delete(work)
        }
    }
}

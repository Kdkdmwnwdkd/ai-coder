package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.AppDatabase
import com.novelseek.ultra.data.model.Outline
import com.novelseek.ultra.data.repository.OutlineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class OutlineEditViewModel(application: Application, private val workId: Long) : AndroidViewModel(application) {
    private val repository: OutlineRepository

    private val _outline = MutableStateFlow<Outline?>(null)
    val outline: StateFlow<Outline?> = _outline

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        val db = AppDatabase.getDatabase(application)
        repository = OutlineRepository(db.outlineDao())
        loadOutline()
    }

    fun loadOutline() {
        viewModelScope.launch {
            _isLoading.value = true
            _outline.value = repository.getByWorkId(workId)
            _isLoading.value = false
        }
    }

    fun saveOutline(content: String) {
        viewModelScope.launch {
            val current = _outline.value
            val outline = if (current != null) {
                current.copy(content = content, updatedAt = System.currentTimeMillis())
            } else {
                Outline(workId = workId, content = content)
            }
            val id = repository.save(outline)
            _outline.value = outline.copy(id = if (outline.id == 0L) id else outline.id)
        }
    }
}

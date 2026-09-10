package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.AppDatabase
import com.novelseek.ultra.data.model.AgentConfig
import com.novelseek.ultra.data.repository.AgentConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AgentConfigViewModel(application: Application, private val workId: Long) : AndroidViewModel(application) {
    private val repository: AgentConfigRepository

    private val _config = MutableStateFlow<AgentConfig?>(null)
    val config: StateFlow<AgentConfig?> = _config

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        val db = AppDatabase.getDatabase(application)
        repository = AgentConfigRepository(db.agentConfigDao())
        loadConfig()
    }

    fun loadConfig() {
        viewModelScope.launch {
            _isLoading.value = true
            _config.value = repository.getOrCreate(workId)
            _isLoading.value = false
        }
    }

    fun updateConfig(
        writingStyle: String? = null,
        continuePrompt: String? = null,
        polishPrompt: String? = null,
        expandPrompt: String? = null,
        ideaPrompt: String? = null
    ) {
        viewModelScope.launch {
            val current = _config.value ?: repository.getOrCreate(workId)
            val updated = current.copy(
                writingStyle = writingStyle ?: current.writingStyle,
                continuePrompt = continuePrompt ?: current.continuePrompt,
                polishPrompt = polishPrompt ?: current.polishPrompt,
                expandPrompt = expandPrompt ?: current.expandPrompt,
                ideaPrompt = ideaPrompt ?: current.ideaPrompt,
                updatedAt = System.currentTimeMillis()
            )
            repository.save(updated)
            _config.value = updated
        }
    }
}

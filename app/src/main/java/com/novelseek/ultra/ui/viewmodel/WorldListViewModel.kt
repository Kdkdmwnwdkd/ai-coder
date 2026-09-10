package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.AppDatabase
import com.novelseek.ultra.data.model.WorldSetting
import com.novelseek.ultra.data.repository.WorldSettingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class WorldListViewModel(application: Application, private val workId: Long) : AndroidViewModel(application) {
    private val repository: WorldSettingRepository
    val worldSettings: Flow<List<WorldSetting>>

    init {
        val db = AppDatabase.getDatabase(application)
        repository = WorldSettingRepository(db.worldSettingDao())
        worldSettings = repository.getByWorkId(workId)
    }

    fun addSetting(name: String, content: String) {
        viewModelScope.launch {
            repository.insert(WorldSetting(workId = workId, name = name, content = content))
        }
    }

    fun updateSetting(setting: WorldSetting) {
        viewModelScope.launch {
            repository.update(setting)
        }
    }

    fun deleteSetting(setting: WorldSetting) {
        viewModelScope.launch {
            repository.delete(setting)
        }
    }
}

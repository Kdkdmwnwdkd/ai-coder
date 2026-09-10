package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.PreferencesDataStore
import com.novelseek.ultra.data.model.UserSettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesDataStore = PreferencesDataStore(application)
    val settings: StateFlow<UserSettings> = preferencesDataStore.userSettings as StateFlow<UserSettings>

    fun updateSettings(settings: UserSettings) {
        viewModelScope.launch {
            preferencesDataStore.updateSettings(settings)
        }
    }
}

package com.inkrealm.novel.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkrealm.novel.data.local.PreferencesDataStore
import com.inkrealm.novel.data.model.UserSettings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesDataStore(application)
    val settings: StateFlow<UserSettings> = prefs.userSettings as StateFlow<UserSettings>

    fun updateSettings(settings: UserSettings) {
        viewModelScope.launch { prefs.updateSettings(settings) }
    }
}

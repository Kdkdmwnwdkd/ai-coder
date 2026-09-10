package com.novelseek.ultra.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novelseek.ultra.data.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class PreferencesDataStore(private val context: Context) {
    companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE = stringPreferencesKey("api_base")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
    }

    val userSettings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            apiKey = prefs[API_KEY] ?: "",
            apiBase = prefs[API_BASE] ?: "https://api.deepseek.com/v1",
            modelName = prefs[MODEL_NAME] ?: "deepseek-chat",
            demoMode = prefs[DEMO_MODE] ?: true
        )
    }

    suspend fun updateSettings(settings: UserSettings) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = settings.apiKey
            prefs[API_BASE] = settings.apiBase
            prefs[MODEL_NAME] = settings.modelName
            prefs[DEMO_MODE] = settings.demoMode
        }
    }
}

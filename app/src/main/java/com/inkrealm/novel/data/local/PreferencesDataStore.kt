package com.inkrealm.novel.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.inkrealm.novel.data.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "inkrealm_settings")

class PreferencesDataStore(private val context: Context) {
    companion object {
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE = stringPreferencesKey("api_base")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val ENABLE_KB = booleanPreferencesKey("enable_kb")
        val EMBEDDING_KEY = stringPreferencesKey("embedding_key")
        val EMBEDDING_URL = stringPreferencesKey("embedding_url")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
        val EMBEDDING_DIM = intPreferencesKey("embedding_dim")
        val ENABLE_SUMMARY = booleanPreferencesKey("enable_summary")
        val ENABLE_ENTITY = booleanPreferencesKey("enable_entity")
        val POLLINATIONS_KEY = stringPreferencesKey("pollinations_key")
    }

    val userSettings: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            apiKey = prefs[API_KEY] ?: "",
            apiBase = prefs[API_BASE] ?: "https://api.deepseek.com/v1",
            modelName = prefs[MODEL_NAME] ?: "deepseek-chat",
            demoMode = prefs[DEMO_MODE] ?: true,
            enableKb = prefs[ENABLE_KB] ?: false,
            embeddingKey = prefs[EMBEDDING_KEY] ?: "",
            embeddingUrl = prefs[EMBEDDING_URL] ?: "https://dashscope.aliyuncs.com/compatible-mode/v1",
            embeddingModel = prefs[EMBEDDING_MODEL] ?: "text-embedding-v3",
            embeddingDim = prefs[EMBEDDING_DIM] ?: 1024,
            enableSummary = prefs[ENABLE_SUMMARY] ?: false,
            enableEntity = prefs[ENABLE_ENTITY] ?: false,
            pollinationsKey = prefs[POLLINATIONS_KEY] ?: ""
        )
    }

    suspend fun updateSettings(settings: UserSettings) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = settings.apiKey
            prefs[API_BASE] = settings.apiBase
            prefs[MODEL_NAME] = settings.modelName
            prefs[DEMO_MODE] = settings.demoMode
            prefs[ENABLE_KB] = settings.enableKb
            prefs[EMBEDDING_KEY] = settings.embeddingKey
            prefs[EMBEDDING_URL] = settings.embeddingUrl
            prefs[EMBEDDING_MODEL] = settings.embeddingModel
            prefs[EMBEDDING_DIM] = settings.embeddingDim
            prefs[ENABLE_SUMMARY] = settings.enableSummary
            prefs[ENABLE_ENTITY] = settings.enableEntity
            prefs[POLLINATIONS_KEY] = settings.pollinationsKey
        }
    }
}

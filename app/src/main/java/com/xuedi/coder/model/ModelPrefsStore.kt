package com.xuedi.coder.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 推理/模型偏好（code 62 CPU 稳定底包）。
 *  · 和 ThemeStore 共用同一套 DataStore Preferences 机制，但文件独立（model_store），
 *    避免清主题误删推理配置。
 *
 * 当前开关（code 62 精简后仅剩默认模型偏好）：
 *  · useFast1_5BDefault：用户首次进入时，若还没手动选模型，默认选 1.5B（true=快模式）。
 *      默认 false = 保持现状=3B。想翻默认只要把 DEFAULT_USE_FAST_1_5B 改成 true。
 *  · Vulkan / GPU 加速开关（已在 v1.3.26-code62 移除）：CMake 侧 XUEDI_HAS_VULKAN=OFF
 *      已是稳定 CPU 底包，用户级开关不再需要。
 */
class ModelPrefsStore(private val ctx: Context) {

    companion object {
        private val KEY_USE_FAST_1_5B = booleanPreferencesKey("use_fast_1_5b_default")

        const val DEFAULT_USE_FAST_1_5B = false
    }

    val useFast1_5BFlow: Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_USE_FAST_1_5B] ?: DEFAULT_USE_FAST_1_5B }

    suspend fun getUseFast1_5B(): Boolean = useFast1_5BFlow.first()

    suspend fun setUseFast1_5B(enabled: Boolean) {
        ctx.dataStore.edit { it[KEY_USE_FAST_1_5B] = enabled }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_store")

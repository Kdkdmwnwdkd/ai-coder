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
 * 推理/模型偏好。
 *  · 和 ThemeStore 共用同一套 DataStore Preferences 机制，但文件独立（model_store），
 *    避免清主题误删推理配置。
 *
 * 当前开关：
 *  · useVulkanAccel：是否允许 LlamaJniEngine 把 n_gpu_layers 传负值（请求全 offload）。
 *      默认 true = 允许；false = 强制 CPU-only(0) 加载，作为最后一档用户级回退开关。
 *
 * code279: useFast1_5BDefault（1.5B 快模式）已按用户要求删除；默认模型固定优先 3B
 *      （ModelManager.autoSelectInitialByPrefs）。
 */
class ModelPrefsStore(private val ctx: Context) {

    companion object {
        private val KEY_USE_VULKAN   = booleanPreferencesKey("use_vulkan_accel")

        const val DEFAULT_USE_VULKAN   = true
    }

    val useVulkanAccelFlow: Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_USE_VULKAN] ?: DEFAULT_USE_VULKAN }

    suspend fun getUseVulkanAccel(): Boolean = useVulkanAccelFlow.first()

    suspend fun setUseVulkanAccel(enabled: Boolean) {
        ctx.dataStore.edit { it[KEY_USE_VULKAN] = enabled }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_store")

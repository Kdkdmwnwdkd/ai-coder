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
 * 推理/模型偏好（对应方案 C & 方案 A 的用户可开关项）。
 *  · 和 ThemeStore 共用同一套 DataStore Preferences 机制，但文件独立（model_store），
 *    避免清主题误删推理配置。
 *
 * 当前开关：
 *  · useFast1_5BDefault：用户首次进入时，若还没手动选模型，默认选 1.5B（true=快模式）。
 *      默认 false = 保持现状=3B。等你验证 Vulkan 效果后，想翻默认只要把下面
 *      DEFAULT_USE_FAST_1_5B 改成 true 即可（1 行改动，不用动别处）。
 *  · useVulkanAccel：是否允许 LlamaJniEngine 把 n_gpu_layers 传负值（请求全 offload）。
 *      默认 true = 允许；false = 强制 CPU-only(0) 加载，作为最后一档用户级回退开关。
 */
class ModelPrefsStore(private val ctx: Context) {

    companion object {
        private val KEY_USE_FAST_1_5B = booleanPreferencesKey("use_fast_1_5b_default")
        private val KEY_USE_VULKAN   = booleanPreferencesKey("use_vulkan_accel")

        // 🎚️ 默认值（v1.3.26-gpu1 初版均保守：3B + 允许 Vulkan）
        // 方案 C 正式翻默认时，改下面这一行即可。
        const val DEFAULT_USE_FAST_1_5B = false
        const val DEFAULT_USE_VULKAN   = true
    }

    val useFast1_5BFlow: Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_USE_FAST_1_5B] ?: DEFAULT_USE_FAST_1_5B }

    val useVulkanAccelFlow: Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_USE_VULKAN] ?: DEFAULT_USE_VULKAN }

    suspend fun getUseFast1_5B(): Boolean = useFast1_5BFlow.first()
    suspend fun getUseVulkanAccel(): Boolean = useVulkanAccelFlow.first()

    suspend fun setUseFast1_5B(enabled: Boolean) {
        ctx.dataStore.edit { it[KEY_USE_FAST_1_5B] = enabled }
    }

    suspend fun setUseVulkanAccel(enabled: Boolean) {
        ctx.dataStore.edit { it[KEY_USE_VULKAN] = enabled }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_store")

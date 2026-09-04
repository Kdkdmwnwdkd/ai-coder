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
 *  · useFast1_5BDefault：用户首次进入时，若还没手动选模型，默认选 1.5B（true=快模式）。
 *      默认 false = 保持现状=3B。
 *  · useVulkanAccel：vc67 起 UI 已移除，永久返回 false（纯 CPU-only 构建，不再允许 Vulkan GPU 卸载）。
 */
class ModelPrefsStore(private val ctx: Context) {

    companion object {
        private val KEY_USE_FAST_1_5B = booleanPreferencesKey("use_fast_1_5b_default")
        @Suppress("unused")
        private val KEY_USE_VULKAN   = booleanPreferencesKey("use_vulkan_accel")

        const val DEFAULT_USE_FAST_1_5B = false
        // 🎚️ vc67: Vulkan UI 已删，此处默认值不影响实际运行（getUseVulkanAccel 被强制硬编码 false）
        const val DEFAULT_USE_VULKAN   = false
    }

    val useFast1_5BFlow: Flow<Boolean> =
        ctx.dataStore.data.map { it[KEY_USE_FAST_1_5B] ?: DEFAULT_USE_FAST_1_5B }

    /** vc67 起永久返回 false：纯 CPU-only 构建，忽略任何历史持久化值，不再走 Vulkan/GPU 分支。 */
    val useVulkanAccelFlow: Flow<Boolean> =
        kotlinx.coroutines.flow.flowOf(false)

    suspend fun getUseFast1_5B(): Boolean = useFast1_5BFlow.first()
    /** vc67 起永久硬编码 false。底层 ModelManager→gpuLayers=0，彻底 CPU-only。 */
    suspend fun getUseVulkanAccel(): Boolean = false

    suspend fun setUseFast1_5B(enabled: Boolean) {
        ctx.dataStore.edit { it[KEY_USE_FAST_1_5B] = enabled }
    }

    /** vc67 起空实现（无 UI 再调；保留签名避免编译期删引用导致其他模块炸）。 */
    suspend fun setUseVulkanAccel(@Suppress("UNUSED_PARAMETER") enabled: Boolean) { }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_store")

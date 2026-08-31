package com.xuedi.coder

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import com.xuedi.coder.model.LlmEngine
import com.xuedi.coder.model.MockLlmEngine
import com.xuedi.coder.model.ModelManager
import com.xuedi.coder.plugin.PluginManager
import com.xuedi.coder.theme.ThemeStore
import com.xuedi.coder.ui.screen.UiBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 【新 M4 = 管理层】Application 完整版：
 *   - 保留 M3 的 Coil ImageLoaderFactory + CoroutineScope
 *   - 新增四个 lazy 单例（管理层三件套 + 推理引擎抽象）：
 *       · themeStore   → DataStore 持久化 背景URI/透明度
 *       · pluginManager→ 插件/场景 管理（Gson代替serialization）
 *       · modelManager → GGUF模型 导入/选/删（Room+SAF，M4仍没接JNI推理）
 *       · llmEngine    → LlmEngine 接口（当前=MockLlmEngine，M5替换为JNI实现）
 *   - onCreate 时：
 *       1) pluginManager.ensureBuiltinPlugins() （先有4个场景，否则PluginsPage空）
 *       2) themeStore 的 Flow 同步到 UiBackground 全局 StateFlow（M3的UI盒子要订阅）
 */
class App : Application(), ImageLoaderFactory, CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
    val appScope: CoroutineScope get() = this

    // ---- 管理层四件套（lazy 首次访问时才创建，加快冷启动）----
    val themeStore: ThemeStore by lazy { ThemeStore(this) }
    val pluginManager: PluginManager by lazy { PluginManager(this) }
    val modelManager: ModelManager by lazy { ModelManager(this) }
    val llmEngine: LlmEngine by lazy { MockLlmEngine() }  // M5 → LlamaJniEngine

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1) 确保 4 个内置场景插件存在，并写入 Room 表
        appScope.launch { runCatching { pluginManager.ensureBuiltinPlugins() } }

        // 2) 把 ThemeStore 持久化的值 同步到 M3 UI 层的 UiBackground StateFlow：
        //    这样 SettingsPage 改透明度 / 选照片 → 写入 DataStore → 下次启动仍生效。
        appScope.launch(Dispatchers.Main.immediate) {
            themeStore.backgroundAlphaFlow.collectLatest { alpha ->
                UiBackground.setAlpha(alpha)
            }
        }
        appScope.launch(Dispatchers.Main.immediate) {
            themeStore.backgroundPathFlow.collectLatest { path ->
                UiBackground.setUri(path)
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val b = ImageLoader.Builder(this)
            .crossfade(true)
            .respectCacheHeaders(false)
        if (BuildConfig.DEBUG) runCatching { b.logger(DebugLogger()) }
        return b.build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

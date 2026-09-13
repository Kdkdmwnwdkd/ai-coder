package com.xuedi.coder

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import com.xuedi.coder.model.ApiConfigStore
import com.xuedi.coder.model.ApiLlmEngine
import com.xuedi.coder.model.LlamaJniEngine
import com.xuedi.coder.model.LlmEngine
import com.xuedi.coder.model.ModelManager
import com.xuedi.coder.model.ModelPrefsStore
import com.xuedi.coder.plugin.PluginManager
import com.xuedi.coder.theme.ThemeStore
import com.xuedi.coder.ui.screen.UiBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * 【v1.3.26-code308】Application：
 *   · 新增 ApiConfigStore + ApiLlmEngine（云端 AI API 推理引擎）
 *   · llmEngine 动态路由：API 模式 → ApiLlmEngine / 本地模式 → LlamaJniEngine
 */
class App : Application(), ImageLoaderFactory, CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
    val appScope: CoroutineScope get() = this

    // ---- 管理层 + 推理偏好 ----
    val themeStore: ThemeStore by lazy { ThemeStore(this) }
    val pluginManager: PluginManager by lazy { PluginManager(this) }
    val modelManager: ModelManager by lazy { ModelManager(this) }
    val modelPrefs: ModelPrefsStore by lazy { ModelPrefsStore(this) }

    /** code308 新增：API 配置存储 */
    val apiConfigStore: ApiConfigStore by lazy { ApiConfigStore(this) }

    // ---- 推理引擎 ----
    private val llamaEngine: LlamaJniEngine by lazy { LlamaJniEngine() }

    /** code308 新增：云端 API 引擎 */
    private val apiEngine: ApiLlmEngine by lazy { ApiLlmEngine(apiConfigStore) }

    /**
     * code308 新增：动态路由 —— 根据设置选择本地或 API 引擎。
     *
     * 设置页「推理来源」切换：
     *   · API 模式 (apiConfigStore.enabled=true) → 调用云端大模型
     *   · 本地模式 (apiConfigStore.enabled=false) → 调用本地 llama.cpp
     */
    val llmEngine: LlmEngine
        get() = if (apiConfigStore.enabled) apiEngine else llamaEngine

    /** 直接获取 Llama 引擎引用（Settings/ModelManager 需要，不受 API 模式影响） */
    fun llamaEngineRef(): LlamaJniEngine = llamaEngine

    /** code308 新增：直接获取 API 引擎引用（ChatViewModel 需要传历史上下文） */
    fun apiEngineRef(): ApiLlmEngine = apiEngine

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1) 确保 4 个内置场景插件存在
        appScope.launch { runCatching { pluginManager.ensureBuiltinPlugins() } }

        // 2) ThemeStore 持久化同步到 UI
        appScope.launch(Dispatchers.Main.immediate) {
            themeStore.backgroundAlphaFlow.collectLatest { alpha -> UiBackground.setAlpha(alpha) }
        }
        appScope.launch(Dispatchers.Main.immediate) {
            themeStore.backgroundPathFlow.collectLatest { path -> UiBackground.setUri(path) }
        }

        // 3) 引擎预热 + 预加载 Room 里的当前模型
        // code308: 如果启用了 API 模式，跳过本地模型预热（不需要加载 GGUF）
        appScope.launch(Dispatchers.Default) {
            if (apiConfigStore.enabled) {
                Log.i(TAG, "预热: API 模式已启用，跳过本地模型预热")
                return@launch
            }

            val llamaSt = LlamaJniEngine.libStatus()
            Log.i(TAG, "预热: Llama lib status: loaded=${llamaSt.first} err=${llamaSt.second}")
            runCatching {
                val (autoselected, _) = modelManager.autoSelectInitialByPrefs()
                if (autoselected) Log.i(TAG, "预热: 已按偏好自动选中初始模型")
            }
            val current = runCatching { modelManager.getSelected() }.getOrNull()
            if (current != null) {
                Log.i(TAG, "预热加载(Llama)：${current.displayName} nCtx=${modelManager.defaultNCtx}")
                val holder = com.xuedi.coder.model.LlamaEngineHolder { llamaEngineRef() }
                val (ok, tip) = runCatching { modelManager.switchAndLoadModel(current.id, holder) }
                    .getOrElse { t -> false to "预热异常：${t.javaClass.simpleName}:${t.message}" }
                Log.i(TAG, "预热结果 ok=$ok tip=$tip")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@App,
                        if (ok) "✓ 模型已自动加载(Llama)：${current.displayName}" else "⚠ $tip",
                        if (ok) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Log.i(TAG, "尚无选中的 GGUF 模型 → Settings → 导入后点「加载并设为当前模型」")
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
        private const val TAG = "XuediApp"
        lateinit var instance: App
            private set
    }
}

package com.xuedi.coder

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
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
 * 【v1.3.25-fix22】单引擎版 Application：
 *   · 彻底移除自写 Qwen 推理器（qwen-jni + QwenInferEngine），按用户死命令物理删除所有自写 C++ 推理代码
 *   · 唯一引擎 = LlamaJniEngine（基于官方 llama.cpp b5180）
 *   · 保留 MockLlmEngine fallback（仅运行期 nativeChat 失败时兜底，不替代真推理）
 */
class App : Application(), ImageLoaderFactory, CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
    val appScope: CoroutineScope get() = this

    // ---- 管理层四件套 + 推理偏好 ----
    val themeStore: ThemeStore by lazy { ThemeStore(this) }
    val pluginManager: PluginManager by lazy { PluginManager(this) }
    val modelManager: ModelManager by lazy { ModelManager(this) }
    val modelPrefs: ModelPrefsStore by lazy { ModelPrefsStore(this) }

    private val llamaEngine: LlamaJniEngine by lazy { LlamaJniEngine() }

    val llmEngine: LlmEngine get() = llamaEngine

    /** 直接获取 Llama 引擎引用（Settings/ModelManager 需要读 libStatus / ctx 等） */
    fun llamaEngineRef(): LlamaJniEngine = llamaEngine

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
        appScope.launch(Dispatchers.Default) {
            val llamaSt = LlamaJniEngine.libStatus()
            Log.i(TAG, "预热: Llama lib status: loaded=${llamaSt.first} err=${llamaSt.second}")
            // 🆕 v1.3.26-gpu1 方案 C：如果用户从没手动选过模型，按【快模式默认 1.5B】偏好
            // 自动在 Room 里 set selected。只在 selected==null 时生效，不覆盖用户明确选择。
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

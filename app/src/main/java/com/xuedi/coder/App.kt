package com.xuedi.coder

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import com.xuedi.coder.model.LlamaJniEngine
import com.xuedi.coder.model.LlmEngine
import com.xuedi.coder.model.MockLlmEngine
import com.xuedi.coder.model.ModelManager
import com.xuedi.coder.model.QwenInferEngine
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
 * 【新 M4 = 管理层】Application 完整版（M5-3 接入 LlamaJniEngine 骨架）：
 *   - 保留 M3 的 Coil ImageLoaderFactory + CoroutineScope
 *   - 管理层四件套 lazy：
 *       · themeStore   → DataStore 持久化 背景URI/透明度
 *       · pluginManager→ 插件/场景 管理（Gson代替serialization）
 *       · modelManager → GGUF模型 导入/选/删（Room+SAF）
 *       · llmEngine    → **LlamaJniEngine 优先（try/catch）失败才 fallback MockLlmEngine**
 *   - onCreate 时：
 *       1) pluginManager.ensureBuiltinPlugins() 写内置 4 场景
 *       2) themeStore Flow → UiBackground StateFlow（M3 的 UI 盒子）
 *       3) 预热 LlamaJniEngine：触发一次 ensureLibLoaded()，提前知道是"JNI真可用"还是"骨架fallback"
 */
class App : Application(), ImageLoaderFactory, CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO
    val appScope: CoroutineScope get() = this

    // ---- 管理层四件套（lazy 首次访问时才创建，加快冷启动）----
    val themeStore: ThemeStore by lazy { ThemeStore(this) }
    val pluginManager: PluginManager by lazy { PluginManager(this) }
    val modelManager: ModelManager by lazy { ModelManager(this) }

    /**
     * 🔴 v1.3.24：引擎双路分发。
     *  QwenInferEngine.useQwenEngine 开关由 Settings 页切换：
     *    · false（默认）→ 走 LlamaJniEngine（v1.3.16 稳定推理路径，已验证魅族20能跑）
     *    · true          → 走 QwenInferEngine（从零写的极简推理器，不依赖 llama_tokenize/llama_decode）
     *
     * 两个引擎各自持有独立 native state（Llama: ctx, Qwen: g_model 单例），切换时自动 release 旧引擎。
     */
    private val llamaEngine: LlamaJniEngine by lazy { LlamaJniEngine() }

    private val qwenEngine: QwenInferEngine by lazy { QwenInferEngine() }

    val llmEngine: LlmEngine by lazy {
        object : LlmEngine {
            private fun active(): LlmEngine =
                if (QwenInferEngine.useQwenEngine) qwenEngine else llamaEngine

            override fun chatFlow(system: String, user: String) = active().chatFlow(system, user)
            override fun cancel() {
                // cancel 两边都调（防止切引擎瞬间旧引擎还在跑）
                runCatching { llamaEngine.cancel() }
                runCatching { qwenEngine.cancel() }
            }
            override fun release() {
                runCatching { llamaEngine.release() }
                runCatching { qwenEngine.release() }
            }
        }
    }

    /** 直接获取 Llama 引擎引用（Settings/ModelManager 需要读 libStatus / ctx 等）*/
    fun llamaEngineRef(): LlamaJniEngine = llamaEngine
    /** 直接获取 Qwen 引擎引用（Settings/ModelManager 需要读 isModelLoaded / lastLoadError 等）*/
    fun qwenEngineRef(): QwenInferEngine = qwenEngine

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1) 确保 4 个内置场景插件存在，并写入 Room 表
        appScope.launch { runCatching { pluginManager.ensureBuiltinPlugins() } }

        // 2) 把 ThemeStore 持久化的值 同步到 M3 UI 层的 UiBackground StateFlow：
        //    这样 SettingsPage 改透明度 / 选照片 → 写入 DataStore → 下次启动仍生效。
        appScope.launch(Dispatchers.Main.immediate) {
            themeStore.backgroundAlphaFlow.collectLatest { alpha -> UiBackground.setAlpha(alpha) }
        }
        appScope.launch(Dispatchers.Main.immediate) {
            themeStore.backgroundPathFlow.collectLatest { path -> UiBackground.setUri(path) }
        }

        // 3) 【v1.3.24】引擎预热 + 预加载 Room 里的当前模型
        //    · useQwenEngine=false → 走 LlamaJniEngine（通过 LlamaEngineHolder + switchAndLoadModel）
        //    · useQwenEngine=true  → 走 QwenInferEngine（通过 switchAndLoadQwenModel，后面在 ModelManager 添加）
        appScope.launch(Dispatchers.Default) {
            val useQwen = QwenInferEngine.useQwenEngine
            Log.i(TAG, "预热：useQwenEngine=$useQwen；分发 wrapper=${llmEngine.javaClass.simpleName}")
            // —— 打印两个引擎的 lib 状态（预热阶段都触发一次 ensureLibLoaded，避免首聊时才加载）——
            runCatching {
                val llamaSt = LlamaJniEngine.libStatus()
                Log.i(TAG, "  · Llama lib status: loaded=${llamaSt.first} err=${llamaSt.second}")
            }
            runCatching {
                val qwenSt = QwenInferEngine.libStatus()
                Log.i(TAG, "  · Qwen  lib status: loaded=${qwenSt.first} err=${qwenSt.second}")
            }
            val current = runCatching { modelManager.getSelected() }.getOrNull()
            if (current != null) {
                val (ok, tip) = if (!useQwen) {
                    // Llama 路径（与 v1.3.23 完全一致）
                    Log.i(TAG, "预热加载(Llama)：${current.displayName} nCtx=${modelManager.defaultNCtx}")
                    val holder = com.xuedi.coder.model.LlamaEngineHolder { llamaEngineRef() }
                    runCatching { modelManager.switchAndLoadModel(current.id, holder) }
                        .getOrElse { t -> false to "预热异常：${t.javaClass.simpleName}:${t.message}" }
                } else {
                    // Qwen 路径
                    Log.i(TAG, "预热加载(Qwen)：${current.displayName}")
                    runCatching { modelManager.switchAndLoadQwenModel(current.id, qwenEngineRef()) }
                        .getOrElse { t -> false to "Qwen 预热异常：${t.javaClass.simpleName}:${t.message}" }
                }
                Log.i(TAG, "预热结果 ok=$ok tip=$tip")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@App,
                        if (ok) "✓ 模型已自动加载(${(if(useQwen)"Qwen" else "Llama")})：${current.displayName}" else "⚠ $tip",
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

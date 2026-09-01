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
     * M5-3 策略：优先 LlamaJniEngine（真JNI）。
     *  什么时候 fallback Mock：
     *   - libxuedi-llama.so 加载失败（极少见，NDK 链路已绿，除非安装包坏）
     *   - LlamaJniEngine 构造/初始化有异常
     *
     * 注：即便 so 加载成功，native 方法在 M5-4 前仍是 stub（无 C++ 实现），
     * 这部分 fallback 在 LlamaJniEngine.chatFlow 内部处理，**不会抛到引擎层外面**。
     */
    val llmEngine: LlmEngine by lazy {
        runCatching { LlamaJniEngine() as LlmEngine }
            .onFailure { t -> Log.e(TAG, "LlamaJniEngine 创建失败，fallback MockLlmEngine：${t.message}") }
            .getOrDefault(MockLlmEngine())
    }

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

        // 3) 【M5-3 + M8 + v1.2.5】预热 LlamaJniEngine + 预加载 Room 里的当前模型
        //    🔴 v1.2.5 关键修复：之前用 eng.loadModel() 直接加载，失败就默默 ctx=0，
        //    用户进入 SettingsPage 看到「已选中 ✓」以为加载好了，但聊天页会报 ctx=0。
        //    现在统一走 modelManager.switchAndLoadModel：
        //      · 保证 nCtx=2048 低内存档
        //      · lastLoadedPath 正确更新（SettingsPage「重新加载」不会重复 load）
        //      · 失败时写入 lastLoadError（SettingsPage 内存状态能显示原因）
        //      · 结果用 Toast 通知用户（用户启动 APP 就能知道模型加载成功/失败）
        appScope.launch(Dispatchers.Default) {
            val eng = llmEngine
            Log.i(TAG, "LlmEngine 预热完成：implementation=${eng.javaClass.simpleName}")
            val libSt = (eng as? LlamaJniEngine)?.run { LlamaJniEngine.libStatus() }
            libSt?.let { (ok, err) ->
                Log.i(TAG, "JNI lib 状态 libLoaded=$ok  error=$err")
            }
            if (eng is LlamaJniEngine) {
                val current = runCatching { modelManager.getSelected() }.getOrNull()
                if (current != null) {
                    Log.i(TAG, "预热加载 GGUF：${current.displayName}（nCtx=${modelManager.defaultNCtx}）")
                    val holder = com.xuedi.coder.model.LlamaEngineHolder { eng }
                    val (ok, tip) = runCatching {
                        modelManager.switchAndLoadModel(current.id, holder)
                    }.getOrElse { t ->
                        false to "预热异常：${t.javaClass.simpleName}:${t.message}"
                    }
                    Log.i(TAG, "预热结果 ok=$ok tip=$tip")
                    // 主线程 Toast 告诉用户结果（这样启动 App 就能感知到成功/失败）
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@App,
                            if (ok) "✓ 模型已自动加载：${current.displayName}" else "⚠ $tip",
                            if (ok) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Log.i(TAG, "尚无选中的 GGUF 模型 → Settings → 导入后点「加载并设为当前模型」")
                }
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

package com.xuedi.coder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.SvgDecoder
import coil.util.DebugLogger
import com.xuedi.coder.data.PluginDatabase
import com.xuedi.coder.model.LlmEngine
import com.xuedi.coder.model.MockLlmEngine
import com.xuedi.coder.model.ModelManager
import com.xuedi.coder.plugin.PluginManager
import com.xuedi.coder.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var pluginDb: PluginDatabase
        private set
    lateinit var pluginManager: PluginManager
        private set
    lateinit var modelManager: ModelManager
        private set
    lateinit var themeStore: ThemeStore
        private set
    /** M6 之前用 Mock 流式引擎；真机跑通后替换为 LlamaJniEngine */
    val llmEngine: LlmEngine by lazy { MockLlmEngine() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        pluginDb = PluginDatabase.get(this)
        pluginManager = PluginManager(this)
        modelManager = ModelManager(this)
        themeStore = ThemeStore(this)
        appScope.launch {
            pluginManager.ensureBuiltinPlugins()
            pluginManager.rescan()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_KEEP,
                    "推理保活",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "前台通知保活，防止Flyme杀掉模型" }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACTION,
                    "动作执行",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "执行ACTION（复制/打开/截图）时的反馈" }
            )
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
                add(GifDecoder.Factory())
            }
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .crossfade(true)
            .build()
    }

    companion object {
        const val CHANNEL_KEEP = "llama_keep"
        const val CHANNEL_ACTION = "action_result"
        lateinit var instance: App
            private set
    }
}

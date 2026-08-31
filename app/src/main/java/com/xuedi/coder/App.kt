package com.xuedi.coder

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.SvgDecoder
import coil.util.DebugLogger
import com.xuedi.coder.model.MockLlmEngine
import com.xuedi.coder.model.ModelManager
import com.xuedi.coder.plugin.PluginManager
import com.xuedi.coder.theme.ThemeStore

/**
 * 【M3 管理层里程碑版本】
 * 这一步只接入各"逻辑管理模块"，不涉及 UI 导航、聊天页面、SAF权限、FileProvider 等，
 * 确保管理层 + Room + 持久化 + 推理引擎接口 这条链路能独立编译通过。
 * 同时为 Coil 实现全局 ImageLoaderFactory（以后加载背景照片需要）。
 */
class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 提前触发一次 lazy 初始化的入口，
        // 以便编译期能确保所有类 + import 都是正确的（不会因为 unused 被误检）。
        runCatching { pluginManager }
        runCatching { modelManager }
        runCatching { themeStore }
    }

    // —— 各管理层单例：M4 UI层会通过 App.instance.xxx 拿到 ——
    val pluginManager: PluginManager by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginManager(this)
    }
    val modelManager: ModelManager by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
        ModelManager(this)
    }
    val themeStore: ThemeStore by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
        ThemeStore(this)
    }
    /** M3~M5 用 Mock 引擎模拟流式打字；M6 再换成真 JNI llama.cpp */
    val llmEngine: com.xuedi.coder.model.LlmEngine by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
        MockLlmEngine()
    }

    // —— Coil 全局图片加载器（以后加载背景照片/模型缩略图用）——
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .respectCacheHeaders(false)
            .components {
                add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
            }
            .runCatching {
                if (BuildConfig.DEBUG) logger(DebugLogger())
                this
            }.getOrElse { this }
            .build()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

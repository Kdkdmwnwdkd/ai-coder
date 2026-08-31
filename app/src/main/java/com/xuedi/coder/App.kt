package com.xuedi.coder

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * 【新 M3 = UI 层】Application 简化版。
 * 暂不接入管理层（PluginManager/ModelManager/ThemeStore）— 见 /tmp/m3_logic_backup，
 * 等 M4=管理层再接回来（届时用 Gson/Moshi 代替 serialization gradle 插件）。
 *
 * UI 层使用：
 *  · Coil 全局 ImageLoaderFactory（照片背景加载）
 *  · 一个后台 CoroutineScope（插件/设置页面的UI动作可以用）
 */
class App : Application(), ImageLoaderFactory, CoroutineScope {

    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

    val appScope: CoroutineScope get() = this

    override fun onCreate() {
        super.onCreate()
        instance = this
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

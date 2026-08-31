package com.xuedi.coder

import android.app.Application

/**
 * 【M1 最小骨架版本】
 * 先不初始化 Room / 插件 / 模型 / 主题 / 图片加载 等复杂模块，
 * 确保 Gradle + AGP + Compose 这套最基础链路能在 GitHub Actions 上 100% 编过。
 * 后续里程碑再把各模块逐个加回。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

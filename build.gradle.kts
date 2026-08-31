// 根 build.gradle.kts —— 终极稳妥方案：
// -----------------------------------------------------------------
// 彻底绕开 Gradle Plugin Portal → marker artifact 这套反复炸的链路。
// 所有插件 JAR 直接通过 buildscript { classpath("真实Maven坐标") } 拉到 classpath，
// 然后子模块用 apply(plugin = "插件ID字符串") 加载。
// 这套语法 Gradle 6/7/8 都 100% 稳定，不存在 UnknownPluginException。
// -----------------------------------------------------------------
// 版本遵循 Android 官方兼容矩阵（2024Q1 稳定组合）：
//   AGP 8.2.2  ↔ Gradle 8.2  ↔ Kotlin 1.9.22  ↔ Compose Compiler 1.5.8  ↔ KSP 1.9.22-1.0.17

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/central"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        maven(url = uri("https://mirrors.cloud.tencent.com/gradle/"))
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        classpath("org.jetbrains.kotlin:kotlin-serialization-gradle-plugin:1.9.22")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:1.9.22-1.0.17")
    }
}

// （保留一个空 plugins 块也可以，我们不写 version 就不会走 Portal 解析）
plugins { /* 真实插件已通过上面 buildscript classpath 注入 */ }

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

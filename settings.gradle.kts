pluginManagement {
    repositories {
        val repos = linkedMapOf(
            "aliyun-google" to "https://maven.aliyun.com/repository/google",
            "aliyun-central" to "https://maven.aliyun.com/repository/central",
            "aliyun-public" to "https://maven.aliyun.com/repository/public",
            "tencent" to "https://mirrors.cloud.tencent.com/gradle/",
            "google" to "https://dl.google.com/dl/android/maven2/",
            "central" to "https://repo1.maven.org/maven2/",
            "gradle-plugin" to "https://plugins.gradle.org/m2/"
        )
        repos.forEach { (_, url) -> maven(url = uri(url)) { isAllowInsecureProtocol = true } }
        gradlePluginPortal()
    }
    // 核心修复：通过 plugin id → 真实 Maven 坐标显式映射，
    // 避免 Gradle 因 Portal 上 marker artifact 缺失导致 "UnknownPluginException"（尤其是 KSP）。
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application",
                "com.android.library" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                "com.google.devtools.ksp" ->
                    useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
                "org.jetbrains.kotlin.android",
                "org.jetbrains.kotlin.jvm",
                "org.jetbrains.kotlin.plugin.serialization" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val repos = linkedMapOf(
            "aliyun-google" to "https://maven.aliyun.com/repository/google",
            "aliyun-central" to "https://maven.aliyun.com/repository/central",
            "aliyun-public" to "https://maven.aliyun.com/repository/public",
            "tencent" to "https://mirrors.cloud.tencent.com/maven/",
            "google" to "https://dl.google.com/dl/android/maven2/",
            "central" to "https://repo1.maven.org/maven2/",
            "jitpack" to "https://jitpack.io"
        )
        repos.forEach { (_, url) -> maven(url = uri(url)) { isAllowInsecureProtocol = true } }
        mavenLocal()
    }
}

rootProject.name = "ai-coder"
include(":app")

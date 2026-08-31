pluginManagement {
    // 双保险：先用官方标准仓库，再用国内镜像做 fallback。
    // GitHub Actions 上官方仓库通常直连很快，而阿里云/mirrors 是给国内用户开发时兜底。
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/central"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        maven(url = uri("https://mirrors.cloud.tencent.com/gradle/"))
    }
    // 终极兜底：Plugin Portal marker 同步慢时，强制用真实 Maven JAR 坐标
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application", "com.android.library" ->
                    useModule("com.android.tools.build:gradle:${requested.version}")
                "org.jetbrains.kotlin.android",
                "org.jetbrains.kotlin.jvm",
                "org.jetbrains.kotlin.plugin.serialization" ->
                    useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
                "com.google.devtools.ksp" ->
                    useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = uri("https://maven.aliyun.com/repository/google"))
        maven(url = uri("https://maven.aliyun.com/repository/central"))
        maven(url = uri("https://maven.aliyun.com/repository/public"))
        maven(url = uri("https://jitpack.io"))
    }
}

rootProject.name = "ai-coder"
include(":app")

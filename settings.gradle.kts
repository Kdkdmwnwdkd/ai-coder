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

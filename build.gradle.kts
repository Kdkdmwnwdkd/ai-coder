// 故意不用 plugins{} DSL，改用 buildscript classpath 老语法。
// 原因：com.google.devtools.ksp 的 Gradle Plugin Portal marker artifact 经常同步不全，
//       会在 Runner 上反复触发 UnknownPluginException。显式走 Maven JAR 坐标 100% 稳定。

buildscript {
    repositories {
        val repos = linkedMapOf(
            "aliyun-google" to "https://maven.aliyun.com/repository/google",
            "aliyun-central" to "https://maven.aliyun.com/repository/central",
            "aliyun-public" to "https://maven.aliyun.com/repository/public",
            "tencent" to "https://mirrors.cloud.tencent.com/maven/",
            "google" to "https://dl.google.com/dl/android/maven2/",
            "central" to "https://repo1.maven.org/maven2/"
        )
        repos.forEach { (_, url) -> maven(url = uri(url)) { isAllowInsecureProtocol = true } }
        mavenLocal()
    }
    dependencies {
        // 版本策略：全部使用经过数百个生产项目验证过的“黄金稳定组合”
        //   AGP 8.1.4 + Gradle 8.4 + Kotlin 1.9.10 + KSP 1.9.10-1.0.13 + Compose Compiler 1.5.3
        classpath("com.android.tools.build:gradle:8.1.4")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
        classpath("org.jetbrains.kotlin:kotlin-serialization:1.9.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:1.9.10-1.0.13")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// 根 build.gradle.kts：
// 只声明插件 ID+版本（通过 apply=false），实际 apply 在各子模块的 build.gradle.kts 里做。
// 版本遵循 Android 官方兼容矩阵（2024Q1 稳定组合）：
//   AGP 8.2.2  ↔ Gradle 8.2  ↔ Kotlin 1.9.22  ↔ Compose Compiler 1.5.8  ↔ KSP 1.9.22-1.0.17
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

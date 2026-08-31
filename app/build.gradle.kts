// =======================================================
// M3 · 管理层里程碑（不碰UI！只加逻辑层依赖 + 逻辑层代码）
// 在 M2 已通过基础上加：
//   - Kotlinx Serialization（插件配置 JSON 化）
//   - DataStore Preferences（背景/插件开关等轻量持久化）
//   - Coil（图片加载 + App 的 ImageLoaderFactory）
//   - Okio（SAF 导入文件时高性能复制）
//   - Coroutines ViewModel（viewModelScope 扩展）
//   - 代码：plugin/（PluginManager/PluginConfig）
//           model/（ModelManager/LlmEngine/MockLlmEngine）
//           theme/（ThemeStore 背景持久化）
//           data/ChatMsg.kt（聊天消息结构）
//   - App.kt：把以上全部实例化，确保初始化逻辑能编译
// =======================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xuedi.coder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xuedi.coder"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0-M3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a") }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "AI编程助手·调试版")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            resValue("string", "app_name", "AI编程助手")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/NOTICE",
            "/META-INF/LICENSE"
        )
    }
}

dependencies {
    // ————— M1 基础依赖（已验证过）—————
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ————— M2 Room（已验证过）—————
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ————— ✨ M3 新增：管理层依赖 ✨ —————
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // DataStore（背景透明度/插件开关等轻量KV）
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    // Serialization（插件配置 JSON 化）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    // Coroutines Android
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Okio（SAF 导入模型/背景时复制文件到私有目录）
    implementation("com.squareup.okio:okio:3.7.0")
    // Coil（背景照片加载 + App 全局 ImageLoader）
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    implementation("io.coil-kt:coil-svg:2.5.0")
}

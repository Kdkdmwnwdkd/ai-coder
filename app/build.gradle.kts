// =======================================================
// 【最小可编译基准版本】—— 先确保能在 GitHub Actions 上 100% 产出 APK
// 之后再逐个加回 Room / KSP / Navigation / Serialization / 插件系统 等功能。
// =======================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xuedi.coder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xuedi.coder"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0.0-M1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a") }
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
    // 官方兼容矩阵：Kotlin 1.9.22 ↔ Compose Compiler 1.5.8
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
    // ————— AndroidX 基础 —————
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ————— Compose 基础（官方 BOM 对齐版本，防版本错配）—————
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

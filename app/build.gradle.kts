// =======================================================
// 【新 M3 = UI 层里程碑】
// 按用户要求把 原M3(管理层) 和 原M4(UI层) 顺序对调了！
// 本层只做 UI：导航 + 4页面 + 底部Tab + 权限 + FileProvider + 照片背景UI盒子
// —— 完全不碰管理层（PluginManager/ModelManager/ThemeStore），它们留在 /tmp/m3_logic_backup
// —— 彻底避开 serialization gradle 插件那个全网找不到 artifact 的坑
// 依赖：全是纯 runtime（不需要 Gradle 插件），MavenCentral 100% 能拉到
//   · Navigation Compose（页面导航）
//   · Material Icons Extended（底部Tab图标）
//   · Lifecycle ViewModel + Coroutines Flow（聊天状态）
//   · Coil Compose（照片背景加载）
//   · Room + KSP（M2 已验证通过保留）
// =======================================================
apply(plugin = "com.android.application")
apply(plugin = "org.jetbrains.kotlin.android")
apply(plugin = "com.google.devtools.ksp")

android {
    namespace = "com.xuedi.coder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xuedi.coder"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.0.0-M3-UI"
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
    // ————— M1 基础依赖（已验证通过）—————
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ————— M2 Room（已验证通过保留，KSP继续工作）—————
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ————— 🌟 新 M3 = UI 层专属依赖（纯 runtime，不需要 Gradle 插件）—————
    // 页面导航（BottomTab 切 4 个页面）
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // Compose ViewModel（聊天/插件/设置 页面状态）
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    // 协程（Flow 流式打字效果模拟）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Material 扩展图标（底部 Tab：聊天/插件/设置/关于）
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    // Compose Foundation（点击/手势/选择器）
    implementation("androidx.compose.foundation:foundation:1.6.0")
    // Coil（选择照片做背景 → 用 AsyncImage/SubcomposeAsyncImage 加载）
    implementation("io.coil-kt:coil-compose:2.5.0")
}

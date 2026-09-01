// =======================================================
// 【M5 = JNI llama.cpp 里程碑】
// 新增：NDK / CMake / preBuild 自动拉 llama.cpp-b4812 源码（避免子模块/手工拷贝坑）
// —— 严格遵循 190155 经验：缺失 llama.cpp/CMakeLists.txt 时，
//    下游 CMakeLists.txt 直接 FATAL_ERROR，绝不生成"可运行但永远stub/假回复"的APK
// =======================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.xuedi.coder"
    compileSdk = 34

    // ---- M5 新增：NDK（Android 官方镜像 setup-java@v5 默认会带 27.x）----
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.xuedi.coder"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.1.0-M5-JNI"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk { abiFilters += listOf("arm64-v8a") }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // ---- M5 新增：CMake 编译参数（llama.cpp 需要 C++17）----
        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O3",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_ARM_NEON=ON"
                )
            }
        }
    }

    // ---- M5 新增：CMake 入口（防stub：缺失 llama.cpp 时 FATAL_ERROR）----
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    // ————— 🌟 新 M4 = 管理层专属依赖（全是纯 runtime JAR，绝不加任何 Gradle 插件！）—————
    // 1) Gson：代替 kotlinx.serialization（那个 gradle 插件全网 404，炸了整个 M3 原顺序）。
    //    Google 官方出品，2008 年存在至今，MavenCentral 坐标 100% 可拉：
    //    https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/
    implementation("com.google.code.gson:gson:2.10.1")

    // 2) DataStore Preferences：ThemeStore 存"背景URI字符串 + 透明度Float"的持久化容器。
    //    Google 官方（属于 AndroidX），MavenCentral 坐标：
    //    https://repo1.maven.org/maven2/androidx/datastore/datastore-preferences/1.0.0/
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}

// =======================================================
// M5 新增：preBuild 前确保 llama.cpp-b4812 源码就位（避免 git submodule 空目录的坑）
// —— 对应经验 190155 / 190266：绝不依赖 submodule 初始化是否成功；
//    缺失时直接用 curl -L 从 GitHub 官方 archive 下固定 tag tar.gz。
//    如果网络失败，Gradle 直接 fail（而不是生成一个"功能全stub的假APK"来骗用户）。
// =======================================================
tasks.register("ensureLlamaCppSource") {
    group = "build"
    description = "确保 llama.cpp 源码存在于 app/src/main/cpp/llama.cpp/（首次构建自动下载 b4812 压缩包）"
    val cppDir = file("src/main/cpp")
    val llamaDir = java.io.File(cppDir, "llama.cpp")
    val marker = java.io.File(llamaDir, "CMakeLists.txt")
    outputs.upToDateWhen { marker.exists() && marker.length() > 40_000 }  // b4812 CMakeLists.txt 60KB+
    doLast {
        if (marker.exists() && marker.length() > 40_000) return@doLast
        cppDir.mkdirs()
        val TAG = "b4812"
        val URL = "https://github.com/ggerganov/llama.cpp/archive/refs/tags/$TAG.tar.gz"
        val tmpTgz = java.io.File(cppDir, "_dl_llama_$TAG.tar.gz")
        println("[ensureLlamaCppSource] llama.cpp 源码缺失，下载 $URL ...")
        // curl -L 跟随重定向；-f 失败返回非 0；--retry 重试；--max-time 300s
        val curlResult = exec {
            isIgnoreExitValue = true
            commandLine(
                "sh", "-c",
                "curl -L -f --retry 3 --retry-delay 2 --max-time 300 -o '${tmpTgz.absolutePath}' '$URL'"
            )
        }
        check(curlResult.exitValue == 0 && tmpTgz.exists() && tmpTgz.length() > 1_000_000) {
            "llama.cpp 源码下载失败（curl exit=${curlResult.exitValue}，文件大小=${tmpTgz.length()}B）。" +
                "请手动将 https://github.com/ggerganov/llama.cpp/archive/refs/tags/$TAG.tar.gz 解压后内容放到 " +
                "${llamaDir.absolutePath}/（目标目录下必须直接有 CMakeLists.txt / ggml.c / llama.cpp 等文件，不能嵌套一层 llama.cpp-b4812）"
        }
        // 清理旧目录
        listOf(llamaDir, java.io.File(cppDir, "llama.cpp-$TAG")).forEach { d ->
            if (d.exists()) d.deleteRecursively()
        }
        // tar xzf → 默认解压出 llama.cpp-b4812/
        val tarResult = exec {
            isIgnoreExitValue = true
            commandLine("tar", "xzf", tmpTgz.absolutePath, "-C", cppDir.absolutePath)
        }
        check(tarResult.exitValue == 0) { "llama.cpp 解压失败 tar exit=${tarResult.exitValue}" }
        val extracted = cppDir.listFiles()?.firstOrNull {
            it.isDirectory && (it.name == "llama.cpp-$TAG" ||
                (it.name.startsWith("llama.cpp") && it.name != "llama.cpp"))
        }
        check(extracted != null && java.io.File(extracted, "CMakeLists.txt").exists()) {
            "解压后找不到 llama.cpp 目录（应包含 CMakeLists.txt）。cppDir 下目录：" +
                cppDir.listFiles()?.filter { it.isDirectory }?.map { it.name }
        }
        check(extracted.renameTo(llamaDir)) { "重命名 ${extracted.name} → llama.cpp 失败" }
        tmpTgz.delete()
        println("[ensureLlamaCppSource] 完成：源码就位 ${llamaDir.absolutePath}，CMakeLists.txt=${marker.length()}B")
    }
}

// 保证所有 NDK CMake 配置任务、preBuild 在跑之前源码先到位
tasks.named("preBuild").configure { dependsOn("ensureLlamaCppSource") }
tasks.whenTaskAdded {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn("ensureLlamaCppSource")
    }
}

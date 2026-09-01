import java.io.File

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

    // ---- M5 NDK：与 workflow build.yml 里安装的版本保持一致 ----
    //      （ubuntu-24.04 Runner 默认不装 NDK，Actions 前一步 sdkmanager --install "ndk;26.1.10909125"）
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.xuedi.coder"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.2.0-M6-Formal-UI"  // v1.2.5：正式版 UI + 预热修复 + 内存状态条
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

    // =====================================================
    // 签名配置（解决"每次出包都要先卸载"的核心根因）
    // 关键：必须放在 buildTypes 之前，否则 buildTypes 里 signingConfigs.getByName("fixedDebug")
    //       会找不到对象（AGP 按代码顺序执行）。
    // 优先级：
    //   1) release：SIGNING_STORE_* / SIGNING_KEY_* 环境变量（Actions Secrets SIGNING_*）
    //      （用户 shimmer_xuedi_release.jks 正式签名，M7 用）
    //   2) fixedDebug：XUEDI_DEBUG_STORE_* / XUEDI_DEBUG_KEY_*
    //      （Debug 统一固定签名，Debug APK 之间可覆盖安装，不用卸载）
    //   3) fallback：本地手跑时自动读 keystore/xuedi-debug.jks
    // =====================================================
    signingConfigs {

        create("release") {
            val envStoreFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
                ?: project.findProperty("RELEASE_STORE_FILE")?.toString()
                ?: rootProject.file("signing/shimmer_xuedi_release.jks")
                    .takeIf { it.exists() }?.absolutePath
            val envStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
                ?: project.findProperty("RELEASE_STORE_PASSWORD")?.toString()
            val envKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
                ?: project.findProperty("RELEASE_KEY_ALIAS")?.toString()
            val envKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                ?: project.findProperty("RELEASE_KEY_PASSWORD")?.toString()
                ?: envStorePassword

            if (envStoreFile != null && File(envStoreFile).exists() &&
                !envStorePassword.isNullOrBlank() && !envKeyAlias.isNullOrBlank()) {
                storeFile = File(envStoreFile)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
                // —— 注意：AGP 8.x 已删除 signingConfig.v2SigningEnabled（deprecated 属性）
                //    只需要 enableV1Signing / enableV2Signing / enableV3Signing / enableV4Signing = true
            }
        }

        create("fixedDebug") {
            val envStore = System.getenv("XUEDI_DEBUG_STORE_FILE")?.takeIf { it.isNotBlank() }
                ?: rootProject.file("keystore/xuedi-debug.jks").absolutePath
                    .takeIf { File(it).exists() }
                ?: rootProject.file("../keystore/xuedi-debug.jks").absolutePath
                    .takeIf { File(it).exists() }

            val envStorePassword = System.getenv("XUEDI_DEBUG_STORE_PASSWORD")
                ?.takeIf { it.isNotBlank() }
                ?: "XuediCoder_Debug_2026!"
            val envKeyAlias = System.getenv("XUEDI_DEBUG_KEY_ALIAS")
                ?.takeIf { it.isNotBlank() }
                ?: "xuedicoder"
            val envKeyPassword = System.getenv("XUEDI_DEBUG_KEY_PASSWORD")
                ?.takeIf { it.isNotBlank() }
                ?: envStorePassword

            if (envStore != null && File(envStore).exists()) {
                storeFile = File(envStore)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            // 统一包名：之前 applicationIdSuffix = ".debug" 导致 debug/release 包名不同 → 覆盖必失败
            resValue("string", "app_name", "AI编程助手·调试版")
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            resValue("string", "app_name", "AI编程助手")
            // release 优先用 release signingConfig（需要 storeFile 已配）；
            // 没配正式 release keystore 时 fallback 到 fixedDebug（能出包能覆盖安装）。
            // 注意：signingConfigs.create("release") 总会创建对象（哪怕属性没设全），
            // 所以不能靠 getByName 抛异常来 fallback——必须显式判断 storeFile != null。
            val releaseCfg = signingConfigs.runCatching { getByName("release") }.getOrNull()
            signingConfig = if (releaseCfg?.storeFile != null) releaseCfg
                            else signingConfigs.getByName("fixedDebug")
        }
    }

    // M6 单测：纯函数（extractActions/friendlyName）走 JVM unit test；
    // isReturnDefaultValues=true 让 Android stub 方法返回默认值而非抛 "not mocked"，
    // 即使测试间接碰到 android.* 类也不会炸（extractActions 本身是纯 Kotlin，不调 Android API）。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
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

    // ————— M6 单测：JUnit 4（JVM unit test，CI 直接 ./gradlew testDebugUnit）—————
    testImplementation("junit:junit:4.13.2")
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
    val llamaDir = File(cppDir, "llama.cpp")
    val marker = File(llamaDir, "CMakeLists.txt")
    outputs.upToDateWhen { marker.exists() && marker.length() > 40_000 }  // b4812 CMakeLists.txt 60KB+
    doLast {
        if (marker.exists() && marker.length() > 40_000) return@doLast
        cppDir.mkdirs()
        // NOTE: 用 llama.cpp 官方真实存在的 release tag（b4835），而不是 short hash。
        //      同时直接写最终组织 ggml-org（ggerganov/llama.cpp 已迁移到 ggml-org/llama.cpp，
        //      archive 路由经过 301 时偶尔会把路径拼成 404）。codeload 直链比 github.com archive 稳定。
        val TAG = "b4835"
        val URLS = listOf(
            "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/$TAG",
            "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$TAG.tar.gz",
            "https://github.com/ggerganov/llama.cpp/archive/refs/tags/$TAG.tar.gz",
        )
        val tmpTgz = File(cppDir, "_dl_llama_$TAG.tar.gz")
        var lastExit = -1
        for ((i, url) in URLS.withIndex()) {
            println("[ensureLlamaCppSource] 下载源 #${i + 1}/${URLS.size} → $url")
            val curlResult = exec {
                isIgnoreExitValue = true
                commandLine(
                    "sh", "-c",
                    "curl -L -f --retry 3 --retry-delay 2 --max-time 300 -o '${tmpTgz.absolutePath}' '$url'"
                )
            }
            lastExit = curlResult.exitValue
            if (curlResult.exitValue == 0 && tmpTgz.exists() && tmpTgz.length() > 1_000_000) break
            tmpTgz.delete()
        }
        check(tmpTgz.exists() && tmpTgz.length() > 1_000_000) {
            "llama.cpp 源码下载失败（所有源失败，最后 curl exit=$lastExit，文件大小=${tmpTgz.length()}B）。" +
                "请手动将 https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/$TAG 解压后内容放到 " +
                "${llamaDir.absolutePath}/（目标目录下必须直接有 CMakeLists.txt / ggml.c / llama.cpp 等文件，不能嵌套一层 llama.cpp-$TAG）"
        }
        // 清理旧目录
        listOf(llamaDir, File(cppDir, "llama.cpp-$TAG")).forEach { d ->
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
        check(extracted != null && File(extracted, "CMakeLists.txt").exists()) {
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

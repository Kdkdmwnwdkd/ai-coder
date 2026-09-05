# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

## Entries

[Android 构建与测试环境]
- Date: 2026-09-05
- Context: Agent 首次在本环境为 ai-coder 项目跑单元测试时从零搭建
- Category: Build Methods
- Instructions:
  - 本项目是 Android App（AGP 8.2.2 / Kotlin 1.9.22 / Gradle 8.2 / compileSdk 34）
  - 本环境默认无 JDK、无 Android SDK，需自行安装：
    - `apt-get update && apt-get install -y openjdk-17-jdk-headless`（Debian 12 需先 update 才能搜到）
    - Android cmdline-tools 下载解压到 /opt/android-sdk/cmdline-tools/latest
    - `yes | sdkmanager --sdk_root=/opt/android-sdk "platforms;android-34" "build-tools;34.0.0" "platform-tools" --licenses`
    - 项目根写 `local.properties`：`sdk.dir=/opt/android-sdk`
  - NDK 26.1.10909125 无需手动装，AGP 构建时自动下载安装
  - 跑单元测试命令（必须后台终端执行，限制内存防 OOM）：
    `./gradlew testDebugUnitTest --no-daemon -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m"`
    （gradle.properties 默认 -Xmx6g 在 8GB 环境会 OOM，命令行覆盖为 3g）
  - 首次构建约 18 分钟（下载 Gradle + 依赖 + llama.cpp b5180 源码 + NDK）；之后增量很快
  - Gradle 日志中 "Unexpected type tag" MessageIOException 是 --no-daemon 模式的无害警告，构建会继续
  - 测试结果在 app/build/test-results/testDebugUnitTest/*.xml

# code81 三问题修复与构建踩坑报告

> 版本: 1.3.26-code81
> 日期: 2026-09-05
> 分支: dev

## 一、修复的三个问题

### P0 — Token 丢失（"你好"回复 0 字符）

**根因**: `LlamaJniEngine.chatFlow` 的 `callbackFlow` 默认 buffer 为 64，当 C++ 层 onToken 回调速度超过 UI collect 速度时，`trySend` 静默丢弃 token，导致 UI 显示空气泡。

**修复**:
1. 在 `callbackFlow {}` 末尾追加 `.buffer(Channel.UNLIMITED)`，彻底消除溢出丢 token。
2. `trySend` 返回值改为显式检查，失败时打 Log：
   ```
   val sendResult = trySend(ChatChunk.Token(text = filtered))
   if (sendResult.isFailure) {
       Log.w(TAG, "⚠️ trySend Token 失败: '${filtered.take(20)}' reason=${sendResult.exceptionOrNull()?.message}")
   }
   ```
   日志里若出现 `ClosedSendChannelException` → 消费者端被取消，不是 buffer 问题。

**修改文件**: `app/src/main/java/com/xuedi/coder/model/LlamaJniEngine.kt`
- L11: 补充 `import kotlinx.coroutines.flow.buffer`
- L464-467: trySend 失败日志
- L549: `.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED)`

---

### P1 — 搜索结果污染后续消息（"你好"显示 568 字节）

**根因**: 旧方案 `WebSearchPlugin.onPreSend` 异步搜索，结果通过 `resultCallback → prependToLatestUserMsg` 注入到"当前话题最后一条 userMsg"。如果搜索慢于用户下一条消息，结果会被注入到错误的消息上。

**修复**:
1. `WebSearchPlugin.onPreSend` 不再做异步搜索，直接 `return input`。
2. 搜索统一走 `ChatViewModel.sendMessage` 里的同步 `searchSync(query, timeoutMs = 15_000L)`。
3. 搜索结果直接绑定到触发搜索的那条 userMsg（通过 `userMsg.id` 更新），不会污染后续对话。

**修改文件**:
- `app/src/main/java/com/xuedi/coder/plugin/WebSearchPlugin.kt` — onPreSend 直接返回
- `app/src/main/java/com/xuedi/coder/vm/ChatViewModel.kt` — sendMessage 内同步搜索 + 15s 超时

---

### P2 — Action 执行不稳定（open_app 打不开）

**根因**:
1. `ACTION_DYNAMIC_HINT` 格式太复杂（列了 8+ 种动作），1.5B 模型记不住，输出格式混乱。
2. `ActionExecutor` 正则只匹配严格的 `<ACTION: name "arg">` 格式，AI 输出 `open_app "pkg"`（不带尖括号）时解析失败。

**修复**:
1. HINT 简化为只保留 `open_app` 一种格式 + 常用包名速查表（设置/微信/抖音/快手/B站/淘宝）。
2. 新增 `PLAIN_ACTION_REGEX` 宽容匹配不带尖括号的格式：
   ```
   \b(open_app|open_browser|...|accessibility_action)\s+("[^"]*"|'[^']*'|\S+)
   ```
3. 新增 `stripQuotes()` 辅助函数，支持双引号/单引号/无引号三种参数格式。
4. 两轮匹配：先匹配 `<...>` 格式；若未命中，再匹配 plain 格式。

**修改文件**:
- `app/src/main/java/com/xuedi/coder/vm/ChatViewModel.kt` — 简化 HINT
- `app/src/main/java/com/xuedi/coder/action/ActionExecutor.kt` — PLAIN_ACTION_REGEX + stripQuotes + 两轮匹配
- `app/src/test/java/com/xuedi/coder/action/ActionExecutorTest.kt` — 新增 4 个宽容解析测试用例

**测试结果**: 17 个单元测试全部通过（13 原有 + 4 新增），0 失败。

---

## 二、构建踩坑记录

### 坑1: JDK 版本不兼容

**现象**: Gradle 构建报 Kotlin 编译错误，JDK 25.0.2 与 Kotlin 1.9.22 / Gradle 8.2 不兼容。

**解决**: 切换到 `mise` 安装的 Java 17.0.2：
```bash
JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 gradle assembleDebug
```

**兼容矩阵**: AGP 8.2.2 ↔ Gradle 8.2 ↔ Kotlin 1.9.22 ↔ JDK 17

---

### 坑2: Android SDK 未安装

**现象**: `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable`。

**解决**: 下载并安装 Android 命令行工具 + 所需组件：
```bash
# 下载命令行工具
curl -L -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip cmdline-tools.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest

# 安装组件
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
  "ndk;26.1.10909125" "cmake;3.22.1"
```

**所需组件清单**（对应 `app/build.gradle.kts`）:
| 组件 | 版本 | 用途 |
|------|------|------|
| platform-tools | latest | adb 等 |
| platforms;android-34 | 34 | compileSdk |
| build-tools;34.0.0 | 34.0.0 | aapt/d8 |
| ndk;26.1.10909125 | 26.1.10909125 | native 编译 |
| cmake;3.22.1 | 3.22.1 | CMake 构建 |

---

### 坑3: cgroup 内存限制导致 OOM Kill（最隐蔽）

**现象**: 构建到 `mergeDebugGlobalSynthetics`（dex 阶段）时，Gradle daemon 崩溃：
```
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
```

**根因**: 容器 cgroup 内存限制只有 **4GB**（`/sys/fs/cgroup/memory.max = 4294967296`），而 `gradle.properties` 里配了 `-Xmx6g`，Java 进程虚拟内存达 9.4GB，被 OOM killer 杀掉。

**解决**: 通过命令行覆盖 JVM 参数（不修改 `gradle.properties`，保留 GitHub Actions 上的 6g 配置）：
```bash
GRADLE_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+UseSerialGC" \
gradle assembleDebug --no-daemon \
  -Dorg.gradle.workers.max=1 \
  -Dorg.gradle.parallel=false \
  -Dorg.gradle.caching=false
```

**关键参数说明**:
| 参数 | 值 | 作用 |
|------|-----|------|
| -Xmx | 1536m | JVM 堆上限（cgroup 4GB 内） |
| -XX:MaxMetaspaceSize | 256m | 元空间上限 |
| -XX:MaxDirectMemorySize | 256m | 直接内存上限 |
| -XX:+UseSerialGC | — | 串行 GC，内存开销最小 |
| workers.max | 1 | 最多 1 个 worker 进程 |
| parallel | false | 禁用并行构建 |

**额外**: native 编译（llama.cpp）也设 `CMAKE_BUILD_PARALLEL_LEVEL=1` 单线程编译，避免 clang 并行吃内存。

---

### 坑4: Gradle Wrapper 下载超时

**现象**: `./gradlew` 尝试下载 `gradle-8.2-bin.zip` 时 `Connection timed out`。

**解决**: 直接用系统已安装的 Gradle 8.2.1（via mise），绕过 wrapper 下载：
```bash
/root/.local/share/mise/installs/gradle/8.2.1/gradle-8.2.1/bin/gradle assembleDebug
```

---

### 坑5: `buffer` 未导入导致编译失败

**现象**: `Unresolved reference: buffer` at LlamaJniEngine.kt:549。

**根因**: `buffer` 是 `kotlinx.coroutines.flow` 的扩展函数，需要显式 import。

**解决**: 补充 `import kotlinx.coroutines.flow.buffer`。

---

## 三、最终构建命令（完整版）

```bash
cd /workspace/ai-coder
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export CMAKE_BUILD_PARALLEL_LEVEL=1

JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
GRADLE_OPTS="-Xmx1536m -XX:MaxMetaspaceSize=256m -XX:MaxDirectMemorySize=256m -XX:+UseSerialGC \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080" \
/root/.local/share/mise/installs/gradle/8.2.1/gradle-8.2.1/bin/gradle assembleDebug \
  --no-daemon \
  -Dorg.gradle.workers.max=1 \
  -Dorg.gradle.parallel=false \
  -Dorg.gradle.caching=false
```

**构建结果**:
- BUILD SUCCESSFUL in 1m 58s
- APK: `app/build/outputs/apk/debug/app-debug.apk` (21MB)
- versionCode=81, versionName=1.3.26-code81
- Native: libxuedi-llama.so (2.9MB) + libomp.so

---

## 四、改动文件清单

| 文件 | 改动 |
|------|------|
| `app/build.gradle.kts` | versionCode 80→81, versionName→1.3.26-code81 |
| `app/src/main/java/com/xuedi/coder/model/LlamaJniEngine.kt` | P0: buffer UNLIMITED + trySend 日志 + import |
| `app/src/main/java/com/xuedi/coder/plugin/WebSearchPlugin.kt` | P1: onPreSend 不再异步搜索 |
| `app/src/main/java/com/xuedi/coder/vm/ChatViewModel.kt` | P1: 同步搜索绑定消息; P2: 简化 HINT |
| `app/src/main/java/com/xuedi/coder/action/ActionExecutor.kt` | P2: PLAIN_ACTION_REGEX + stripQuotes + 两轮匹配 |
| `app/src/test/java/com/xuedi/coder/action/ActionExecutorTest.kt` | P2: 新增 4 个宽容解析测试 |

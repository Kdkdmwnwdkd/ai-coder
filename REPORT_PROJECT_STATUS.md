# AI编程助手 · 项目状态报告

> 用途：上下文丢失时直接读这份文件就能无缝续上。
> 最近更新：2026-09-01 · M1-M7 全部完成 · 最新成功构建 Run#39（commit `b2d7258`，tag `v1.0.0`）✅
> 仓库：`Kdkdmwnwdkd/ai-coder`（public，默认分支 main）
> GitHub Release v1.0.0：https://github.com/Kdkdmwnwdkd/ai-coder/releases/tag/v1.0.0

---

## 一、项目目标 + 非功能约束（🔴 死要求，不得改）

| 项 | 要求 |
|---|---|
| 应用名 | 「AI编程助手」（用户可见名，**不含「雪帝」字样**；debug 版显示「AI编程助手·调试版」） |
| 视觉风格 | 纯白扁平（Material3，浅色主题） |
| 背景功能 | 聊天页支持自定义照片背景 + 透明度调节（Coil 加载，DataStore 持久化 URI+alpha） |
| 插件场景 | 4 个内置插件场景：android_dev / java_backend / python_script / shell_gradle（`app/src/main/assets/plugins/`） |
| ACTION 标签 | 模型回复中可带 ACTION 标签，消息下方渲染可点按钮执行（openApp 等类型） |
| 推理方式 | **纯本地 GGUF**，JNI 调 llama.cpp，不联网（断网可用） |
| 目标设备 | 魅族 20（12GB RAM，arm64-v8a） |
| 签名/包名 | applicationId=`com.xuedi.coder`；Debug 固定签名（可覆盖安装不用卸载）；Release 用 `shimmer_xuedi_release.jks` |
| 包体约束 | 只编 `arm64-v8a` 一个 ABI；llama + ggml 全静态链接进 `libxuedi-llama.so` |

---

## 二、里程碑进度总览（M1→M7）

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 基础依赖 + Compose UI 骨架 + Gradle 工程 | ✅ Done |
| M2 | Room 持久化（ModelDao / PluginDao） | ✅ Done |
| M3 | UI 4 页面（聊天/插件/设置/关于）+ Navigation + Coil 照片背景 | ✅ Done |
| M4 | 管理层 Gson + DataStore + ThemeStore + PluginManager | ✅ Done |
| M5 | JNI llama.cpp 真推理集成 | ✅ Done（构建通过，**待真机验证**） |
| M6 | ACTION 执行完善（openUrl/share + 健壮性 + 单测） | ✅ Done |
| M7 | Release 打包（签名 fallback + release Job + GitHub Release v1.0） | ✅ Done（v1.0.0 已发布） |

### M5 子步骤进度

| 子步骤 | 内容 | 状态 |
|---|---|---|
| M5-1 | NDK/CMake 工具链 + llama.cpp 源码自动拉取（`ensureLlamaCppSource` task） | ✅ |
| M5-2 | CMakeLists.txt 静态链接 llama+ggml（删 -lcommon，全静态合并） | ✅ |
| M5-3 | LlamaJniEngine Kotlin 骨架 + InferenceForegroundService 前台保活 + fallback Mock | ✅ |
| M5-4 | JNI 真推理（callbackFlow 流式 + nativeChat 回调 + nativeChatCancel 取消） | ✅ 构建通过 |
| M5-真机 | 下载 APK → 魅族 20 五步验证 | 🔴 待做（需物理设备） |

### M6 子步骤进度（✅ 全部完成）

| 子步骤 | 内容 | 状态 |
|---|---|---|
| M6-1 | ActionExecutor 完善：open_app 找不到包→Toast+应用商店(market://)→浏览器 Play→设置页三级 fallback；新增 open_url / share；新增 friendlyName() | ✅ |
| M6-2 | ChatPage 消息卡片下方加 ACTION 按钮 LazyRow 横向滚动，点击执行 + Toast 反馈 ✅/❌ | ✅ |
| M6-3 | extractActions JVM 单测（13 case 覆盖各场景）+ build.gradle testOptions + testImplementation junit | ✅ |
| M6-4 | commit + push，Run#36 (commit b0dae84) build-debug ✅ | ✅ |

### M7 子步骤进度（✅ 全部完成）

| 子步骤 | 内容 | 状态 |
|---|---|---|
| M7-1 | build.yml 加 build-release job（tag v* 触发 :app:assembleRelease，签名 fallback fixedDebug） | ✅ |
| M7-2 | build.yml 加 publish-release job（tag v* 时下载 APK 挂 GitHub Release） | ✅ |
| M7-3 | 打 tag v1.0.0 → Run#37 失败（storeFile missing）→ 修复 → 重打 tag → Run#39 ✅ → Release v1.0.0 已发布 | ✅ |

---

## 三、失败历史 + 根因 + 修复对照表（⚠️ 下次炸先查这张表）

> 编号说明：Run#N 指 GitHub Actions 第 N 次运行（`run_number`）。commit SHA 是唯一可信标识。
> 早期部分编号取自 commit message 中的 `#N`，可能与 GitHub run_number 略有出入，**以 commit SHA 为准**。

| Run / commit | 阶段 | 错误摘要 | 根因 | 修复方式 | 结果 |
|---|---|---|---|---|---|
| #22/23 `eb1c2eb` | 基础设施 | 编译炸：缺 import + 废弃 API + 无 NDK | ① `import java.io.File` 缺失 ② `v2SigningEnabled` 已废弃 ③ Runner 未装 NDK/CMake ④ keystore base64 解码多换行 | 补 import / 删废弃属性 / workflow 装 `ndk;26.1.10909125`+`cmake;3.22.1` / `printf '%s'` 代替 `echo` | ✅ 过 |
| #24 `743340d` | llama 源码 | `ensureLlamaCppSource` 404 | 用了 short hash（非 release tag），codeload 路由 404 | 加 3 镜像回退（codeload / archive / ggerganov） | ✅ 过 |
| #25 `32e4ace` | llama 源码 | 全 DNS 失败 / 404 | ggerganov/llama.cpp 已迁移到 ggml-org，旧链接全断 | 换官方真 release tag `b4835` + ggml-org codeload 直链 | ✅ 过 |
| #26 `c39f0a2` | CMake 链接 | `buildCMakeDebug 41/41 FAILED ld -lcommon not found` | EXAMPLES=OFF 时 common 库不存在，但 CMakeLists 仍引用 | 删 common 依赖 + llama/ggml/ggml-cpu/ggml-base 全静态合并 | ✅ 过 |
| — `501ed36` | M5-3 骨架 | （功能提交，非修错） | — | LlamaJniEngine 骨架 + 前台服务 + App.kt 注入 + fallback Mock | ✅ 过 |
| #28 `67a0a28` | Kotlin 编译 | `compileDebugKotlin Conflicting companion` | App.kt 里有重复 companion object | 合并 App.kt 重复 companion object | ✅ 过 |
| #29 `f35f099` | Kotlin 编译 | 3 处 Unresolved reference | `modelManager.getCurrent` / `current.localPath` 命名错 | `getCurrent` → `getSelected()`；`current.localPath` → `filePath` | ✅ 过 |
| #32 `3805c4f` | C++ 编译 | `cannot jump from this goto statement to its label` | NDK clang 严格 C++：`goto cleanup_and_return` 跳过非平凡对象（sampler 等）初始化 | 删 `cleanup_and_return` label；所有取消/错误路径**就地** `sampler_free + DeleteGlobalRef + return` | → 触发 #33 |
| #33 `8d9aae1` | Kotlin 编译 + 语义 | ① `inferred type is String but Throwable was expected` ② Done 把 reason 当 full | ① `ChatChunk.Error(msg: String)` 但构造器第一参是 `Throwable` ② `ChatChunk.Done(full, stopReason)` 把 stop reason 传成了 full 正文 | ① `Error(RuntimeException(msg), msg)` ② callbackFlow 内 `fullSb` 累积所有 onToken，onDone 时 `Done(fullSb.toString(), reason)` | → 触发 #34 |
| **#34 `77f7c5f`** | **最终** | — | — | 两处 Kotlin bug 修完 | **✅ SUCCESS** |
| #36 `b0dae84` | M6+M7 首推 | — | — | M6 ACTION 完善 + 单测 + M7 release job（debug 验证） | **✅ SUCCESS** |
| #37 `b0dae84` | M7 release | `packageRelease FAILED: SigningConfig "release" is missing required property "storeFile"` | `signingConfigs.create("release")` 总会创建对象（哪怕 Secrets 没配属性没设全），`getByName("release")` 不抛异常 → 原 `runCatching{...}.getOrNull() ?: fixedDebug` 的 `?:` 永不触发，release 用了 storeFile=null 的不完整配置 | 改成显式判断 `if (releaseCfg?.storeFile != null) releaseCfg else fixedDebug` | → 触发 #39 |
| **#39 `b2d7258`** | **M7 release 最终** | — | — | release 签名 fallback 到 fixedDebug（debug keystore），出 release APK + GitHub Release v1.0.0 | **✅ SUCCESS** |

### ChatChunk 签名（写错就重犯 #33 的错，务必照抄）

```kotlin
// app/src/main/java/com/xuedi/coder/model/LlmEngine.kt
sealed class ChatChunk {
    data class Token(val text: String) : ChatChunk()
    data class Done(val full: String, val stopReason: String = "stop") : ChatChunk()
    data class Error(val t: Throwable, val hint: String = t.message ?: "推理出错") : ChatChunk()
}
```

- `Error` 第一参是 **Throwable**，不是 String
- `Done` 第一参 `full` 是**完整回复正文**（UI 侧 `ChatViewModel` 用它 `extractActions`），不是 stop reason
- LlamaJniEngine 在 `callbackFlow` 里用 `fullSb: StringBuilder` 累积所有 `onToken(piece)`，`onDone(reason)` 时传 `Done(fullSb.toString(), reason)`

---

## 四、关键文件 + 代码作用速查（全部绝对路径，可直接点）

### 4.1 Gradle / CI
- [build.gradle.kts](file:///workspace/xuedi-coder/app/build.gradle.kts) — applicationId=`com.xuedi.coder`，NDK 26.1，CMake 3.22.1，签名配置（release/fixedDebug），`ensureLlamaCppSource` task
- [build.yml](file:///workspace/xuedi-coder/.github/workflows/build.yml) — Actions：装 NDK+CMake → 解码 debug keystore → `:app:assembleDebug` → 上传 APK artifact（`AI编程助手-debug-apk`，保留 90 天）
- [settings.gradle.kts](file:///workspace/xuedi-coder/settings.gradle.kts) / [gradle.properties](file:///workspace/xuedi-coder/gradle.properties)

### 4.2 UI 4 页面（`app/src/main/java/com/xuedi/coder/ui/screen/`）
- [ChatPage.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/ui/screen/ChatPage.kt) — 聊天主界面，流式气泡 + ACTION 按钮
- [SettingsPage.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/ui/screen/SettingsPage.kt) — 导入 GGUF / 选背景照片 / 透明度
- [PluginsPage.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/ui/screen/PluginsPage.kt) — 插件场景列表
- [AboutPage.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/ui/screen/AboutPage.kt)
- [AppNavHost.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/ui/screen/AppNavHost.kt) — 底部 Tab 导航
- [BackgroundContainer.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/ui/screen/BackgroundContainer.kt) — 照片背景容器

### 4.3 MVVM 业务（`app/src/main/java/com/xuedi/coder/`）
- [vm/ChatViewModel.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/vm/ChatViewModel.kt) — 收集 ChatChunk：Token 累加显示，Done 时 `extractActions(chunk.full)` 出 ACTION
- [model/LlmEngine.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/model/LlmEngine.kt) — 接口 + ChatChunk sealed class（签名见 §三）
- [model/LlamaJniEngine.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/model/LlamaJniEngine.kt) — 真 JNI 引擎：`ensureLibLoaded` → `nativeInit` 加载 GGUF → `chatFlow` callbackFlow 流式 → `nativeChatCancel` 取消；失败 fallback Mock
- [model/MockLlmEngine.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/model/MockLlmEngine.kt) — 占位引擎（JNI 失败时用）
- [model/ModelManager.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/model/ModelManager.kt) — GGUF 导入/列表/`getSelected()`
- [model/InferenceForegroundService.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/model/InferenceForegroundService.kt) — 前台服务保活（Flyme 后台不被杀）
- [action/ActionExecutor.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/action/ActionExecutor.kt) — ACTION 执行器（M6 要完善：openApp/openUrl/share）
- [plugin/PluginManager.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/plugin/PluginManager.kt) / [PluginConfig.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/plugin/PluginConfig.kt)
- [theme/ThemeStore.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/theme/ThemeStore.kt) — DataStore 存背景 URI + alpha

### 4.4 JNI / C++（`app/src/main/cpp/`）
- [llama_jni.cpp](file:///workspace/xuedi-coder/app/src/main/cpp/llama_jni.cpp) — 4 个 native 方法全流程：
  - `JNI_OnLoad`：缓存 3 个 methodIDs（onToken/onDone/onError）+ `llama_backend_init`
  - `nativeInit`：加载 GGUF + 创建 ctx
  - `nativeChat`：拼 ChatML → tokenize → 预填充 → sampling chain(top_k/top_p/temp/dist) → while(sample→accept→token_to_piece→Java onToken)
  - `nativeRelease` / `nativeChatCancel`：销毁 / 设 g_cancel 原子标志跳出 decode while
- [llama_jni_stub.cpp](file:///workspace/xuedi-coder/app/src/main/cpp/llama_jni_stub.cpp) — stub（已不用，CMakeLists 改用 llama_jni.cpp）
- [CMakeLists.txt](file:///workspace/xuedi-coder/app/src/main/cpp/CMakeLists.txt) — llama + ggml + ggml-cpu + ggml-base 静态链接（不写 common）

### 4.5 App / 清单
- [App.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/App.kt) — Application，注入 LlamaJniEngine
- [MainActivity.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/MainActivity.kt)
- [AndroidManifest.xml](file:///workspace/xuedi-coder/app/src/main/AndroidManifest.xml)

### 4.6 数据层（`app/src/main/java/com/xuedi/coder/data/`）
- [ChatMsg.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/data/ChatMsg.kt) / [ModelEntity.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/data/ModelEntity.kt) / [PluginEntity.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/data/PluginEntity.kt)
- [ModelDao.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/data/ModelDao.kt) / [PluginDao.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/data/PluginDao.kt)
- [ModelDatabase.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/data/ModelDatabase.kt) / [PluginDatabase.kt](file:///workspace/xuedi-coder/app/src/main/java/com/xuedi/coder/data/PluginDatabase.kt)

---

## 五、现在要做的事 + 之后要做的事（按优先级 🔴🟡🟢）

> M6/M7 已全部完成。剩余唯一工作 = 🔴 真机验证（需物理魅族 20，AI 无法代劳）。

### 🔴 最紧：下载 APK → 真机验证（需物理魅族 20，AI 无法代劳）

两个可用 APK：
- **Debug APK**：`https://github.com/Kdkdmwnwdkd/ai-coder/actions` 最新 Run#39 → Artifacts → `AI编程助手-debug-apk`（18.4 MB）
- **Release APK**：`https://github.com/Kdkdmwnwdkd/ai-coder/releases/tag/v1.0.0`（GitHub Release v1.0.0，15.2 MB）

> 推荐 release APK（更小、更接近正式版）。两者签名一致（都走 fixedDebug），可互相覆盖安装。

**真机验证 5 步（每步必测，测完才算 M5 真完成）：**

1. **装 APK** → 下载 → 传魅族 20 安装。签名固定，应能直接覆盖旧版（若要卸载说明签名还错，到 §三 追加新条目）。
2. **背景照片** → 设置页选照片 → 透明度调到 0.2 → 回聊天页看背景盒子是否出现照片。
3. **真模型 + 真流式推理（核心！）** →
   - 下载 `Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf`（ModelScope，约 2GB）放手机
   - 设置 →「导入 GGUF 模型」→ 选它 → 等复制 →「已导入模型列表」出现 →「设为当前」
   - 聊天页问「用 Kotlin 写一个 Android 按钮点击弹吐司」→ **看气泡逐字跳出**（真 JNI 吐 token）→ 结尾自动停 → **有 ACTION 标签的话，消息下方会出现「复制」按钮，点击执行 + Toast 反馈**（M6 新增）
4. **取消推理** → 发长问题 → 中途返回键退出聊天页 → Logcat 过滤 `nativeChatCancel` 出现 cancel=true → 再进聊天页不卡
5. **魅族 Flyme 保活** → 设置「后台运行无限制」+「自启动」+「锁最近任务卡片」→ 开始长推理 → 锁屏切走 3 分钟 → 解锁回来推理还在推 token

### 🟡 真机通过后的后续优化（非阻塞）
- 用户提供 `shimmer_xuedi_release.jks` 后配 Secrets `SIGNING_*` → release 自动切正式签名（无需改代码，`build.gradle.kts` 已判断 storeFile）
- release asset 文件名中文被处理成 `AI.`（`AI编程助手-...` 变 `AI.-...`），如需纯英文名可改 build.yml 的 OUT 变量
- take_screenshot 需要 MediaProjection（权限+前台服务+ImageReader），当前仅 Toast 占位

### ✅ 已完成里程碑（无需再动）
- M1-M4 基础/UI/Room/管理层
- M5 JNI llama.cpp 真推理（构建通过，待真机验证推理效果）
- M6 ACTION 完善（open_app 三级 fallback / open_url / share / 横向按钮 / 13 case 单测）
- M7 Release 打包（build-release job + publish-release job + GitHub Release v1.0.0 已发布）

---

## 六、快速恢复 Checklist（上下文丢失时按 1~6 步走）

1. **拉代码**：`git clone https://github.com/Kdkdmwnwdkd/ai-coder.git` → `cd ai-coder` → 读本文件（`REPORT_PROJECT_STATUS.md`）
2. **查最新 Run**：`https://github.com/Kdkdmwnwdkd/ai-coder/actions` 看最后一条 success 还是 failure
3. **若 success** → 直接下载 Artifact → 做 §五 真机 5 步验证
4. **若 failure** → 看构建日志 Step「失败摘要」→ 对照 §三 失败历史表定位根因 → 修代码
5. **commit + push**：`git add -A && git commit -m "Fix Run#N: ..." && git push`
6. **等 Actions 跑完** → 回到第 2 步

---

## 七、Token / Secrets / 模型 速查表

| 项 | 值 / 说明 |
|---|---|
| GitHub 用户名 | `Kdkdmwnwdkd` |
| 仓库名 | `Kdkdmwnwdkd/ai-coder`（public） |
| GitHub Token | `ghp_***REDACTED***`（⚠️ 真值见会话/凭据来源；secret scanning 会拦截含完整 token 的 push，**报告中勿写明文**；用完及时撤销） |
| Debug 签名 Secrets（已配） | `XUEDI_DEBUG_KS_B64` / `XUEDI_DEBUG_STORE_PASSWORD` / `XUEDI_DEBUG_KEY_ALIAS` / `XUEDI_DEBUG_KEY_PASSWORD` |
| Release 签名 Secrets（M7 待配） | `SIGNING_STORE_FILE`（或 `SIGNING_STORE_FILE_B64`）/ `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD` |
| Debug keystore 本地 | `keystore/xuedi-debug.jks`（密码 `XuediCoder_Debug_2026!`，alias `xuedicoder`） |
| Release keystore 本地 | `signing/shimmer_xuedi_release.jks`（密码见 `signing/README.md`，**仓库内无此文件**） |
| 推荐模型 | `Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf`（约 2GB，ModelScope 下载） |
| llama.cpp 版本 | release tag `b4835`（ggml-org codeload 直链） |
| NDK / CMake | `26.1.10909125` / `3.22.1` |
| 目标 ABI | `arm64-v8a`（只编一个） |

---

## 八、离线代码 snapshot 位置

- 上一会话曾备份到 `/tmp/xuedi-report/`（共 3423 行源码）—— **临时目录，重启已丢失**。
- 现在的 source of truth = **本仓库 main 分支**。clone 即得全部代码。
- 关键文件路径见 §四（全部可点击 `file:///` 链接，前提是工作区在 `/workspace/xuedi-coder`）。

---

## 附：构建产物

### Run#39（commit `b2d7258`，tag `v1.0.0`）— 最新成功构建 ✅

| Artifact | 大小 | 说明 | 保留 |
|---|---|---|---|
| `AI编程助手-release-apk` | 12.6 MB | release APK（fixedDebug 签名，可覆盖安装）| 90 天 |
| `AI编程助手-debug-apk` | 18.4 MB | debug APK | 90 天 |
| `gradle-build-release-log` | 1.7 KB | release 构建日志 | 30 天 |
| `gradle-build-debug-log` | 1.5 KB | debug 构建日志 | 30 天 |

### GitHub Release v1.0.0

- URL：https://github.com/Kdkdmwnwdkd/ai-coder/releases/tag/v1.0.0
- 资产：`AI.-release-b2d7258-arm64-v8a.apk`（15.2 MB，release APK，文件名中文被处理成 `AI.`，不影响下载）

APK 内含真推理 `libxuedi-llama.so`（非 stub 假回复）。

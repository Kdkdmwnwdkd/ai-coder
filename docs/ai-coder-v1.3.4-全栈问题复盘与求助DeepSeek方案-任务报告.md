# AI编程助手 v1.3.4 — 全栈问题复盘 · 踩坑清单 · 求助 DeepSeek 顶层重构方案

> 📋 报告打包时间：2026-09-01 / commit `fb1db0b` / versionCode=15 / v1.3.4
> 🎯 **写给 DeepSeek：请你基于这份完整复盘，给一个「停止打补丁式修复」的**顶层重新设计 **方案（架构 + 真机可运行的落地步骤列表）**。
> 之前 DeepSeek 给的「OOM 预检 + n_batch 调 256 + 信号捕获」那套**已经全部落地但问题没根本解决**（见下文第六部分「已落地但未解决的问题」），所以这一次请**不要**重复这些补丁式建议，直接给更高层的架构级方案。

---

## 一、应用最开始的目标（从未变过）

> 这是一个**纯离线、纯本地推理**的 AI 编程助手 Android App。**不需要联网就能跑完整的 AI 回答**。所有代码全在本地仓库。

| 维度 | 目标 |
|---|---|
| **核心功能** | 手机/平板本地加载 Qwen2.5-3B-Instruct GGUF 模型，对用户输入给出真流式逐字回答（仿 ChatGPT 打字机效果） |
| **当前目标模型** | `Qwen2.5-3B-Instruct Q4_K_M GGUF`（文件 2007MB / 本地推理实测约 8~15 token/s 中端旗舰） |
| **最低目标设备** | **魅族 20** — 骁龙 8 Gen2 / 12GB RAM / arm64-v8a / Android 14 Flyme |
| **兼容目标设备** | **荣耀平板 v8 Pro** — 天玑 8100 / 8GB RAM / arm64-v8a / MagicOS 7（类 Android 13） |
| **次要功能（均已实现 UI）** | ① 聊天页 流式气泡 + 新对话 ；② 4 个场景开关（Android/Java/Python/Shell，注入 System Prompt）；③ 自定义深色模式 3 档（浅色/深色/跟随系统）；④ 设置页导入 GGUF、模型切换、重新加载、推理诊断、背景照片自定义透明度 |
| **架构栈** | Kotlin + Jetpack Compose + DataStore/Room + C++ JNI 调 llama.cpp（b4835）+ GitHub Actions CI（build APK + publish GitHub Release） |
| **硬约束** | 全程离线；不调任何云端 API；最终 APK 约 80~130MB（含 llama.so + libc++_shared.so） |

---

## 二、当前代码全景图（按架构层）

### 2.1 目录结构
```
/workspace/ai-coder/
├── app/
│   ├── build.gradle.kts                     ← versionCode=15 / minSdk=26 / targetSdk=34 / cmake
│   ├── src/main/
│   │   ├── java/com/xuedi/coder/
│   │   │   ├── App.kt                       ← Application 启动：预热加载模型
│   │   │   ├── ui/screen/
│   │   │   │   ├── AppNavHost.kt            ← 底部 Tab 导航：对话/场景/设置/关于
│   │   │   │   ├── ChatPage.kt              ← 聊天页气泡UI + 流式逐字渲染
│   │   │   │   ├── PluginsPage.kt           ← 场景开关页（LazyColumn<List<PluginConfig>>）
│   │   │   │   ├── SettingsPage.kt          ← 设置页（⚠️ 本文件踩坑最多）
│   │   │   │   └── AboutPage.kt             ← 关于页（下载指南/FAQ/开源许可）
│   │   │   ├── model/
│   │   │   │   ├── LlmEngine.kt             ← 引擎接口 chatFlow()/loadModel()/cancel()
│   │   │   │   ├── LlamaJniEngine.kt        ← ⭐ 核心：Kotlin ↔ C++ JNI 桥
│   │   │   │   ├── MockLlmEngine.kt         ← fallback：native 挂时返回占位内容
│   │   │   │   ├── ModelManager.kt          ← Room DAO：导入/删除GGUF、设为当前
│   │   │   │   └── ChatDb.kt                ← Room：Message / Conversation 表
│   │   │   ├── plugin/PluginManager.kt      ← 场景开关 Room CRUD + 4 默认场景 enabled=false
│   │   │   ├── vm/ChatViewModel.kt          ← chatFlow.collect 写 messages State
│   │   │   └── ui/theme/                    ← Material3 主题 + 3 档深色模式
│   │   └── cpp/
│   │       ├── CMakeLists.txt               ← llama.cpp 静态链接 + xuedi-llama.so
│   │       ├── llama_jni.cpp                ← ⭐⭐ 核心 C++：nativeInit/nativeChat/nativeFree
│   │       └── llama/                       ← llama.cpp 源码子目录（b4835 ggml + qwen 图实现）
│   └── .github/workflows/build.yml          ← CI：debug/release arm64-v8a → Release
├── docs/
│   ├── ai-coder-v1.3.2-推理卡死闪退完整诊断包.md    ← 之前的 DeepSeek 诊断输入包
│   └── （本文件）ai-coder-v1.3.4-……任务报告.md      ← 你现在看的这份
```

### 2.2 核心调用链路（每次发送消息时）
```
用户在 ChatPage 点「发送」
  └─ ChatViewModel.sendMessage(systemPrompt, userText)        [Dispatchers.Default]
       └─ LlamaJniEngine.chatFlow(system, user)
            ├─ 内存预检 checkMemAndReason(2000MB, "chatFlow")  ← 小于2GB直接return错误
            └─ callbackFlow {
                 launch(Default) { nativeChat(ctx, system, user, callback) }   ← C++ 阻塞跑
                 launch(Default) { delay(45_000); 超时取消 }
                 callback.onToken(piece) → send(TokenChunk)
               }
                    ↓ JNI
C++ llama_jni.cpp nativeChat()：
  1) 拼 ChatML 字符串：<|im_start|>system\n{sys}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant
  2) llama_tokenize(add_spec=0, parse_spec=1)  → 拿 tokens[] + n_prompt
  3) 对齐检查：tokens[0]==BOS 就剥掉；tokens[0]==EOS 就直接 return error
  4) 预填充：while(n_consumed < n_prompt) → llama_batch_add + llama_decode(n_batch=256)
            每 n_batch 调一次 JNI onPrefillProgress(consumed, total) 回 UI 显示进度
  5) 生成循环：while(n_gen < 1024) → llama_sampler_sample → llama_token_to_piece → JNI onToken → llama_decode
  6) 异常：SIGSEGV/SIGABRT 被 sigaction 捕获 → 写 crash_log.txt → cb_error → 抛 Java
```

---

## 三、踩过的**所有坑**清单（按时间顺序，含根因）

### 📍 P0 级：真机上「完全不能用」的致命坑（共 10 个）

| # | 版本/commit | 现象 | 根因 | 修复 | 还在？ |
|---|---|---|---|---|---|
| P0-1 | v1.2.7 之前 | 点发送立刻 ANR 5s 后被杀 | `nativeChat()` 阻塞 call 跑在 **Main 线程**（没切 Dispatchers.Default） | chatFlow 内部 + ViewModel 外层都切 `Dispatchers.Default` | ✅ 已修 |
| P0-2 | v1.2.4 之前 | 导入后聊天直接报错：`tokenizer 返回 -1214` | 把 `llama_tokenize(buffer=nullptr)` 返回的**负值当错误抛**；其实负值 =「需要的 token 数的绝对值」（llama.cpp 历史遗留，官方 demo 都 abs()） | `abs(need)` 当 buffer 大小，第二次返回负值才是真错误 | ✅ 已修 |
| P0-3 | v1.2.8 | AI 一直转圈圈 + 空气泡（偶尔出几字又消失） | `ChatViewModel` 里写的是 `.collectLatest { }` 收 chatFlow。Token 间隔 20~30ms 时 collectLatest 每次新 token 都 cancel 上一个。最终写入 Message 正文 = 空串 | `collectLatest` → 改 `.collect { }`（保留累积逻辑） | ✅ 已修 |
| P0-4 | v1.3.2~v1.3.3 | **魅族20 点发送 20~36s 闪退 + 0 token；荣耀8G 能蹦出几个字（但不完整）**（核心问题） | 第 1 层：`llama_tokenize(add_spec=1, parse_spec=0)` → ① ChatML 字符串已经有 `<|im_start|>`，结果 add_spec=1 又额外塞 BOS→ChatML 错位 0 token；② parse_spec=0 把 `<|im_start|>` 拆成一串字符 token → 荣耀能蒙、魅族直接 decode 越界 SIGSEGV | `add_spec=0、parse_spec=1`；加 BOS 对齐检查；SIGSEGV/SIGABRT 捕获写 crash_log | ⚠️ 部分修（参数对了，闪退依然需要用户装新版验证） |
| P0-5 | v1.3.3 | **加载失败 —— 所有设备导入模型都显示「加载失败」**，完全不进 nativeInit | 内存预检阈值 `loadModel < 4GB / chatFlow < 3GB`。但 `ActivityManager.MemoryInfo.availMem` **不含 cached/zram 可回收部分**：魅族 12GB avail=2.8~3.2GB / 荣耀 8GB avail=2.5~3GB 都 < 4GB，被预检直接 return false | 阈值 `4096→3000MB / 3072→2000MB`；加 `memSnapshot()` 所有字段打 Logcat | ✅ 已修（阈值正确与否待魅族/荣耀真机反馈） |
| P0-6 | v1.3.4 `2113e93` | SettingsPage 模型卡显示「加载失败：」**后面空字符串**；状态点了也不刷新 | `val engineSnapshot = remember(allModels) { ... }` — key 用的是「Room 已导入模型列表」，但 `lastLoadError`/`currentCtx` 写回时 allModels 根本不变 → remember 永远不重算，UI 永停在初始空快照 | 去掉 remember，每次重组直接读 `@Volatile` 字段（纯原子读无 IO） | ✅ 已修 |
| P0-7 | v1.3.4 `2113e93` | 用户反馈「好像没有重新加载/诊断的按键」 | **两个问题合起来：** (a) 「🔄 重新加载」按钮在模型卡片的**第三行**（屏幕底部时被 TabBar 裁掉看不到）；(b) **设置页/场景页最外层 Column 没加 verticalScroll，超过屏幕高度静默 clip 且滚不动**（🔍 推理诊断卡在 ModelsCard 下面，直接被裁在屏幕外，用户永远以为没有） | (a) 按钮移到卡片第二行（状态条上面）；(b) **SettingsPage Column 加 `.verticalScroll(rememberScrollState())`（PluginsPage 不能加，见 P0-9）** | ✅ 已修（fb1db0b 正确） |
| P0-8 | v1.3.4 `d6d4add` | CI 编译直接挂：两个 job 都失败 | 给 PluginsPage 加 verticalScroll 时漏写 2 个 import：`rememberScrollState` / `verticalScroll` → Kotlin `Unresolved reference` | 补 import | ⚠️ 补完后触发 P0-9 |
| P0-9 | v1.3.4 `06d3420` | **用户反馈：点「场景」/「设置」立刻闪出（闪退）**（最夸张的一个 UI bug） | P0-7 修复时图快 **PluginsPage 也顺手加了 verticalScroll**，但该页内容本来就是 `LazyColumn{ items() }`。Compose 规定：**LazyColumn 不能嵌在同方向可滚动父容器里** → 进页面立即 `IllegalStateException: measured with infinity max height`。至于设置页闪退——如果装到 06d3420 那版是嵌套滚动尺寸冲突（LazyColumn 类问题）；如果是 2113e93 则另有原因（见「未解决清单」） | **PluginsPage 外层 verticalScroll 全删**（LazyColumn 自身就会滚）。Settings 无 LazyColumn，保留 outer verticalScroll 是安全的 | ✅ 已修（fb1db0b） |
| P0-10 | 历史各版本 | Release APK 上传 CI job 偶发 skipped（因为 tag 反复被删重打） | 每次修 bug 都 `git tag -d v1.3.4 + git push --force --tags`，有时旧 tag 的 Release asset 不被新 build job 识别覆盖 | 统一用 `git push origin main --tags --force` + workflow 改成 `tag.push on: always` | ✅ 现在稳定 |

### 📍 P1 级：功能/体验问题（已修 5 条）
| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| P1-1 | 设置页背景照片透明度 Slider 编译挂 `Unresolved: mutableFloatStateOf` | 新增诊断卡时 import 被删了一个 | 补 `androidx.compose.runtime.mutableFloatStateOf`（v1.3.4 commit `52334df`） |
| P1-2 | 深色模式用户要自定义（3 档：浅/深/跟随系统） | 之前只支持跟随系统 | DataStore ThemeModeStore + 设置页 3 个 Chip（v1.3.2 已实现） |
| P1-3 | App 启动时默认所有场景开关都打开 → Prompt 长 + token 多 → 闪退概率升 | 之前 enabled 默认 true | 4 个内置场景写 Room 时 enabled=false（`PluginManager.kt`） |
| P1-4 | 预填充 2000+ token 耗时 15-30s，UI 一直"白转圈圈"无反馈 | C++ 层只有首 token 才回调 | 新增 `onPrefillProgress(consumed, total)` 接口，ChatPage 显示"预填充 37%" |
| P1-5 | n_batch=512 与 Qwen2.5 对齐检查冲突、也略占内存 | 默认 batch 太大 | n_batch 512 → 256（同时可减少 decode 时 OOM） |

---

## 四、核心代码（修复过的关键片段 = 你判断是否还有结构性问题的依据）

### 4.1 LlamaJniEngine.kt 内存预检（L90-L139 最新版）
```kotlin
private data class MemSnapshot(val availMB: Long, val totalMB: Long, val thresholdMB: Long,
                               val lowMemory: Boolean, val appHeapMB: Long)
private fun memSnapshot(): MemSnapshot {
    val am = App.instance.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val info = am?.let { ActivityManager.MemoryInfo().also { mi -> it.getMemoryInfo(mi) } }
    val rt = Runtime.getRuntime()
    return MemSnapshot(
        availMB     = info?.availMem?.div(1024L*1024L) ?: Long.MAX_VALUE,
        totalMB     = info?.totalMem?.div(1024L*1024L) ?: -1L,
        thresholdMB = info?.threshold?.div(1024L*1024L) ?: -1L,
        lowMemory   = info?.lowMemory ?: false,
        appHeapMB   = rt.maxMemory() / (1024L*1024L)
    )
}
private fun checkMemAndReason(minMB: Long, stage: String): String? {
    val m = memSnapshot()
    Log.i(TAG, "[mem:$stage] avail=${m.availMB}MB total=${m.totalMB}MB thr=${m.thresholdMB}MB " +
        "low=${m.lowMemory} heap=${m.appHeapMB}MB (need>=$minMB)")
    return when {
        m.availMB < minMB -> "可用内存不足（avail=${m.availMB}MB < 最低 ${minMB}MB）。\n" +
            "【快照】total=${m.totalMB}MB LMK-thr=${m.thresholdMB}MB 进LMK=${m.lowMemory}\n" +
            "请：① 关后台 ② 重启手机 ③ 再试"
        m.lowMemory && m.availMB < minMB * 2 -> null   // 进入LMK但够2x需求，放行，别误杀
        else -> null
    }
}
// loadModel 前：checkMemAndReason(3000, "loadModel")
// chatFlow  前：checkMemAndReason(2000, "chatFlow")
```

### 4.2 llama_jni.cpp tokenize 参数 + BOS 修正（L394-L487 最新版）
```cpp
// 参数：Qwen ChatML 必须 add_spec=0（不加BOS）/ parse_spec=1（解析<|im_*|>为special token）
int32_t add_spec   = 0;
int32_t parse_spec = 1;
int32_t need = llama_tokenize(state->vocab, prompt.c_str(), (int32_t)prompt.size(),
                              nullptr, 0, add_spec, parse_spec);
// 🔴 负值表示「绝对值是需要的 buffer size」（不是错误！历史版本都有）
int32_t est = (need > 0) ? need : (-need);
int32_t cap = std::min(std::max(est + 8, 64), std::max(64, state->n_ctx - 16));
tokens.resize((size_t)cap);
int32_t real = llama_tokenize(state->vocab, prompt.c_str(), (int32_t)prompt.size(),
                              tokens.data(), cap, add_spec, parse_spec);
if (real <= 0) { /* 二次扩大 buffer 重试 */ }
tokens.resize((size_t)real);

const llama_token bos = llama_vocab_bos(state->vocab);
const llama_token eos = llama_vocab_eos(state->vocab);
// 🔴 BOS 错位自动剥离（add_spec=0 时某些 llama 版本仍会塞 BOS）
if (!tokens.empty() && tokens[0] == bos) {
    for (int i = 1; i < (int)tokens.size(); i++) tokens[i-1] = tokens[i];
    tokens.pop_back();
}
if (!tokens.empty() && tokens[0] == eos) { /* 直接报 ChatML 模板错 */ }
```

### 4.3 SettingsPage.kt 引擎快照（L107-L127 最新版）
```kotlin
// ✅ 不用 remember(key=allModels)！否则 lastLoadError 写完 UI 不刷
val engSnapRaw = (App.instance.llmEngine as? LlamaJniEngine).let { eng ->
    val libSt = eng?.run { LlamaJniEngine.libStatus() }
    LoadDiagSnapshot(
        libLoadedOk     = libSt?.first,
        libLoadError    = libSt?.second,
        currentCtx      = eng?.currentCtx() ?: 0L,
        lastLoadError   = eng?.lastLoadError(),
        lastLoadedPath  = App.instance.modelManager.lastLoadedPath()
    )
}
val engineSnapshot = engSnapRaw  // 每次重组重拿，UI实时

// 模型卡片：顺序= 行1(名/大小/标签/删除) → 行2(🔄重新加载按钮) → 行3(内存状态/红字错误)
// 之前按钮放在行3会被屏幕底部截断，现在行2立刻可见
```

### 4.4 SettingsPage.kt / PluginsPage.kt 外层布局滚动规则（最新 fb1db0b）
```kotlin
// ⚠️ SettingsPage — 没有 LazyColumn，全用 Column + Card，所以必须用 verticalScroll：
Column(
    Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())   // ← 必须，否则诊断卡被屏幕外静默裁剪
        .padding(start=14.dp, end=14.dp, top=10.dp, bottom=34.dp)
) { AppearanceCard(); ModelsCard(); DiagnosticCard(); BuildInfoText() }

// ⚠️ PluginsPage — 列表用 LazyColumn，外层**绝对不能**加 verticalScroll！
//    否则 IllegalStateException 闪退。LazyColumn 自己会滚。
Column(
    Modifier.fillMaxSize().padding(start=14, end=14, top=14, bottom=34)
) { HeaderRow(); Description(); LazyColumn(...) { items(scenes) { Card(...)} } }
```

---

## 五、魅族 20 vs 荣耀平板 v8Pro 设备差异记录（关键观察）

| 对比项 | 魅族 20（骁龙 8 Gen2，12GB，Flyme Android 14） | 荣耀平板 v8 Pro（天玑 8100，8GB，MagicOS 7） |
|---|---|---|
| 导入模型成功？ | ✅ 能 | ✅ 能 |
| 加载模型（loadModel/nativeInit）成功？ | ❌ v1.3.3 时：availMem=2.8~3.2GB < 4GB 被误杀 → v1.3.4 修阈值后**未实测**（这是 fb1db0b 待验证项目） | ❌ v1.3.3 时：availMem=2.5~3GB < 4GB 被误杀 → 同待验证 |
| 点发送能出字吗？ | ❌ 20~36s 闪退 / 0 token | ⚠️ 能蹦出几个字（不完整/经常提前停） |
| tokenize 返回值 | **需要安装 fb1db0b 抓诊断日志确认（现在未知）** | |
| 内存机制差异 | Flyme LMK 杀后台激进，availMem 不含 cached/zram 导致账面低 | MagicOS/Harmony 系统服务占 4.5GB+，8GB 机实际 free 很低 |
| ABI | 都 arm64-v8a ✅ 一致 | |
| 架构差异 | Qualcomm Kryo + Adreno GPU（无 GPU offload，纯 CPU 推理） | MediaTek Kompanio（纯 CPU 推理） |

---

## 六、**已落地（上次 DeepSeek 建议）但问题未根本解决的内容**
> 这部分很重要——**不要在新方案里再重复这些**：
> 1. ✅ 内存预检 loadModel/chatFlow 阈值检查
> 2. ✅ n_batch 从 512 → 256
> 3. ✅ 信号捕获 SIGSEGV/SIGABRT → 写 crash_log 回 Java
> 4. ✅ 预填充进度 `onPrefillProgress()` 回调 UI
> 5. ✅ 场景默认 enabled=false（缩短 Prompt）
> 6. ✅ 超时从 15s → 45s
> 7. ✅ 旧 ctx 释放防止重复加载 SIGSEGV

---

## 七、**当前仍未解决的阻塞问题清单（fb1db0b 装到真机上后需要验证的）**
> 这些是为什么用户说"一直拆下去没用"的根本原因——修复了一层又触发下一层。

| # | 问题 | 状态 | 根因假设 | 失败影响 |
|---|---|---|---|---|
| UN-1 | **魅族 20 点发送闪退（0 token / 20~36s SIGSEGV）** | ❌ 未验证新版 | 多因素组合：tokenize 参数错位已修，但可能还有：(a) `ggml` matmul 在骁龙8 Gen2 上指令集不兼容（`__ARM_FEATURE_*` 编译 flag）；(b) nativeChat 内 tokens/n_batch/n_ctx 边界整数溢出越界写；(c) kv_cache 预分配失败但返回值没查 | App 闪退 → 用户以为坏了 |
| UN-2 | **设置页点击后闪退**（用户最新反馈） | ❌ 未验证新版 | 若用户装的是 P0-9 那版（06d3420）→ 是 LazyColumn 嵌套滚动崩，已修；若装的是 2113e93 → 原因未知，可能和 `engineSnapshot` 在重组时触发某些主线程 Room/锁冲突有关 | 设置功能不可用 → 诊断不了 |
| UN-3 | **加载到内存** 在魅族/荣耀上到底能不能过？ | ❌ 未验证新版 | 阈值从 4GB 降到 3GB 后是否还被误杀？如果 availMem=2500MB 还低于 3000MB，需要再降到 2.5GB（但这会触及「真 OOM 发生在 native decode 时→SIGSEGV」的风险） | 加载不过 → 连推理都开始不了 |
| UN-4 | 荣耀平板 8GB 能蹦出几个字但不完整 | ❌ 未验证新版 | 可能是 kv_cache size 不够或 `n_ctx` 被设小（默认 2048？），或者 sample 时 temperature/top_p 触发提前 EOS | 回答不完整 = 功能打折 |

---

## 八、🚨 给 DeepSeek 的**明确请求**（请你直接输出顶层重构方案）

> 请**不要**再在上述 7 条已落地补丁上叠加新补丁。请你**换思路**，从架构级回答以下 4 个问题，每个问题都要有：**具体方案 + 落地步骤 + 为什么之前的补丁路线解决不了它**。

### ❓ 问题 1：如何彻底消除「魅族 20 闪退 / 荣耀 8GB 半残」的设备差异？
当前是"一个参数调两台机"的暴力模式。请你给一个**引擎层可配置策略**方案（不是调单个数字）：
- 是要引入 `arm64-v8a` 下 `ggml` 的「CPU 能力探测（fp16/int8/i8mm）→ 自动选择 backend」？
- 是要 `n_ctx / n_batch / kv_cache_type(f16 vs q8_0)` 做成**按机型自动选择的 profile**（读取 Build.DEVICE / Build.BOARD / totalMem → 自动落一组参数）？
- 还是要**把 llama.cpp 从 b4835 升到某个稳定版**（请给具体 commit/版本号，并说明升级时需要重写 llama_jni.cpp 的哪些 API）？
- 请附：为什么之前「n_batch 256」不够？（单数字调参 vs 策略层）

### ❓ 问题 2：SettingsPage/PluginsPage 这种「Compose 滚动配置错一行就炸」的问题，怎么从架构上避免？
现在的模式是：**每次我（TRAE agent）改布局时，都有可能忘记「LazyColumn vs Column.verticalScroll」的规则，或者 padding 没留够**，直接写出 IllegalStateException。请你给一个「Compose 页面模板化 + 编译期约束」的方案：
- 要不要做一个 `AppScaffoldPage(contentPadding: PaddingValues, content: ...)` 的基类模板，**强制选择**：要么 `Type.LazyList`（内容自己滚）要么 `Type.ScrollableColumn`（外层滚），而不是随手写 Column.fillMaxSize()？
- 要不要加**自定义 Compose Lint 规则**（基于 `com.android.tools.lint:lint-api`），检测到 `Column(verticalScroll)` 的 direct children 里出现 `LazyColumn/LazyRow` 就直接报错，阻止编译？
- 请附：为什么之前「发现了就修一行」不够（开发者/agent 总忘）？

### ❓ 问题 3：诊断信息收集链路能不能从「手动点按钮」变成「全自动 + 写入文件 + 一键导出分享」？
当前诊断卡只有用户点了「▶️ 开始诊断」才跑，而且用户必须手动截屏或复制。如果魅族20 进设置就闪退（UN-2），那诊断卡根本没机会点开。请给一个**全程自动的诊断体系**：
- App 启动就写 `/sdcard/Android/data/com.xuedi.coder/files/diagnostics/boot-<ts>.txt`，包含：机型/RAM/availMem/native 加载结果/所有 native JNI 的调用参数？
- Native 层 crash（SIGSEGV/SIGABRT）捕获后自动把 crash_log.txt + 内存快照 + 最近 30 条 logcat tag=LlamaJniEngine **打包成 .zip 放在 Download 目录**，同时弹通知「AI编程助手刚崩溃，点此把诊断包发作者」？
- SettingsPage 加「📤 导出诊断包」单个按钮一键生成 zip（Intent.ACTION_SEND 直接唤起微信/邮件）？
- 请附：为什么「手动点开始诊断 + 抓截图」这条路走不通（用户会因闪退连页面都进不去）？

### ❓ 问题 4：内存预检的 3GB/2GB 数字到底怎么算出来才科学？
当前是「我估算 + 试错」：模型文件 2007MB + kv_cache 350MB + 激活 600~800MB ≈ 3GB。但 ActivityManager.MemoryInfo.availMem 并不等于真正可用的 native mmap 字节数。请给一个**校准机制**：
- 是不是在 `nativeInit` 里**先做一次 mmap 压力测试**（依次尝试 mmap 1GB / 2GB / 3GB 连续匿名页，成功就 munmap，能拿到「真正可分配的连续 native RAM」——而不是靠 ActivityManager 半瞎猜）？
- 是不是引入 `android.app.ActivityManager.getMyMemoryState()` / `Debug.getNativeHeapAllocatedSize()` / `/proc/self/status VmRSS` 三个源合并成「真实可 mmap 预算」公式？
- 请附：为什么之前「改阈值数字」永远不够（每个 ROM、杀后台策略、zram 配置都不同）？

---

## 九、附：你需要的话可以直接读的代码路径（全部在 /workspace/ai-coder/）
- 引擎 JVM 桥：`app/src/main/java/com/xuedi/coder/model/LlamaJniEngine.kt`
- 引擎 C++ JNI：`app/src/main/cpp/llama_jni.cpp`
- 设置页（bug 最多）：`app/src/main/java/com/xuedi/coder/ui/screen/SettingsPage.kt`
- 场景页（LazyColumn 陷阱）：`app/src/main/java/com/xuedi/coder/ui/screen/PluginsPage.kt`
- CI 构建：`.github/workflows/build.yml`
- 之前的完整诊断包：`docs/ai-coder-v1.3.2-推理卡死闪退完整诊断包.md`

---

_报告结束。请 DeepSeek 基于以上所有信息输出一份**不重复已落地内容**的架构级重构方案，列出每个改动的 (a) 文件位置 (b) 改动伪代码 (c) 为什么它能从根上解决问题 (d) CI 构建时要注意的编译参数。_

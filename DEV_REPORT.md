# AI 编程助手 · 开发全记录报告

> 版本：v1.3.26-code100 · 包名 `com.xuedi.coder` · 提交数：140+
> 最后更新：2026-09-06

---

## 一、这个项目是什么

一个跑在安卓手机上的 AI 编程助手，四大能力：

1. **本地大模型推理**：llama.cpp JNI 直编进 APK，GGUF 模型手机本地跑，不依赖网络（Qwen2.5 系列，ChatML 格式）
2. **插件系统**：`@搜索`、`@github`、`@复制` 等指令由插件直接处理，跳过 LLM
3. **无障碍操控手机**：通过 AccessibilityService 打开 App、搜索、发消息、点坐标
4. **GitHub 出包闭环**：手机上给代码 → 直推 GitHub → Actions 自动构建 → 手机下载新 APK

---

## 二、现在正在做的事（code100）

**目标：用户在手机上粘贴代码 → App 直接推到 GitHub → 自动出包。**

用户原话：「我要的是我给代码给他，然后他给我出包，或者他直接写代码，然后直接上传到 Github 然后出包」。

code100 落地的三件事：

| 改动 | 文件 | 说明 |
|---|---|---|
| `@github 提交 <路径> + 代码` | GitHubPlugin.kt | Contents API 直推 dev 分支，手机无需本地 Git，push 自动触发构建 |
| 触发构建分支 main → dev | GitHubPlugin.kt | main 停在 code62 旧代码，dev 才是最新 |
| QQ/微信禁用自动化 | ActionExecutor.kt | 微信风控太狠（用户账号差点被封），这两个 App 一律不碰 |

配套已完成：App 克隆对话框自动过滤"分身"选主应用（CoderAccessibilityService.handleAppChooser）。

---

## 三、版本演进全记录

### 阶段 1：基座（M1 → M5）
- **M1**：极简白灰 4Tab UI / GGUF 导入 / 照片背景 / 4 编程场景插件
- **M2**：Room 数据库层（6 个 Entity/Dao/Database）
- **M3**：管理层四件套（PluginManager / ModelManager / ThemeStore / MockLlmEngine）
- **M3-UI**：对调顺序先出 UI 包，纯 runtime 依赖绕开 serialization gradle 插件坑
- **M4**：ActionExecutor 正则解析 `<ACTION...>` 标签（复制/打开 App）
- **M5**：NDK/CMake 集成 llama.cpp，JNI 真推理，preBuild 自动拉 b4812 源码

### 阶段 2：可用（M6 → v1.2.9）
- **M6+M7**：ACTION 完善 + Release 打包 + 固定 Debug 签名（解决每次要卸载才能覆盖）
- **M8**：多话题/多会话侧边栏
- **v1.2.4**：llama_tokenize 返回负值不是错误，取 abs() 作 buffer 大小
- **v1.2.7**：真推理 ANR 致命修复——阻塞 JNI 放后台线程
- **v1.2.8**：collectLatest 丢 token 根因（30ms 一个 token 时永远 cancel 上一个 → 空气泡）→ 改 collect；TRAE 风气泡 UI

### 阶段 3：魅族20 闪退长征（v1.3.0 → v1.3.16）
- 内存预检 4GB 误杀（availMem 不含 cached/zram，魅族20 实际只有 2.8~3.2GB）
- n_batch=1 绕开 batch 切换崩溃
- llama_batch_init(1) 预分配替代循环 malloc/free
- prefill 最后 token + generate 每 token 必须 logits=1（8f90796 关键修复）
- b4835 → b5180 升级治 arm64 batch SIGABRT

### 阶段 4：乱码根治（v1.3.20 → v1.3.25）
- **EOS 判断错**：ChatML 实际结束是 `<|im_end|>`(151645) 不是 `<|endoftext|>` → 永远不命中 EOS → 写满 1024 token 后半段全是乱码
- **BOS 缺失**：add_spec=0 + 手动插 BOS，模型没 BOS 就"困惑"发散成乱码
- **KV cache 没清**：第二次发消息不显示
- **采样器顺序错**：正确顺序 penalties → top_k → top_p → temp → dist

### 阶段 5：自写 Qwen 推理器的弯路（v1.3.24-25，最终全部删除）
想绕过 llama.cpp 在魅族20 的崩溃路径，自写 ggml 极简推理器，连环踩坑：
- Q4_K_M 反量化完全错（照搬 b5180 重写）
- GGUF v3 规范搞反：全部固定 uint64 LE，不是 ULEB128
- `ggml_type_size(Q4_K_M)` 算成 256，实际 144 字节
- tensor offset 必须相对 weights_start，不是 r.off
- Q5_K dequant 未实现走 F16 fallback → 把二进制当 float 读 → 垃圾值
- dequant 返回 scratch 后 free() → 0.01 秒闪退
- **fix22 终局：物理删除全部自写 C++，回归官方 llama.cpp 单引擎**

### 阶段 6：性能 + 插件 + 出包闭环（v1.3.26，code 58 → 100）
- code 58-61：性能优化三件套 + Vulkan GPU 开关（后临时禁用，Actions 上 35min 卡死）
- code 62：稳定底包（main 分支停在这里）
- code 73-82：动态注入 + 异步 @搜索 + 快手包名映射 + SearXNG 七实例 + 搜索失败降级
- code 78：GitHub Actions 插件 + 无障碍系统级操控
- code 94-99：@github 跳浏览器修复 / Token 清洗 / LazyColumn 闪退根治
- **code 100：手机提交代码 → dev 分支 → 自动出包闭环**

---

## 四、踩过的坑（完整清单）

### 🔨 构建 / CI 坑

| # | 坑 | 解法 |
|---|---|---|
| 1 | `plugins{}` DSL 反复 UnknownPluginException（Plugin Portal marker 404） | buildscript classpath 直接写真实 Maven JAR 坐标 |
| 2 | kotlinx-serialization gradle 插件全网找不到 artifact | 彻底分手，换 Gson（纯 runtime 不依赖 gradle 插件） |
| 3 | gradle.properties 写死 localhost 代理 → Runner Connection refused | 代理只走 -D 参数或本地 ~/.gradle |
| 4 | llama.cpp 短 hash 非 tag 下载 404 | 换官方真 release tag b4835 + codeload 直链 + 3 镜像回退 |
| 5 | C++ goto 跳过非平凡对象初始化（NDK clang 严格模式） | 就地 sampler_free + DeleteGlobalRef + return |
| 6 | KDoc 里 `plugins/*.json` 的 `/*` 组合 → KSP Unclosed comment | 改写措辞避开 lexer 边界 |
| 7 | `const val` + `trimIndent()`（运行期函数）编译失败 | 改 `val` |
| 8 | 每次都要卸载才能覆盖安装 | 统一固定 Debug 签名 + 统一包名 |
| 9 | CMake `ld -lcommon not found`（EXAMPLES=OFF 时 common 不存在） | 删 common 依赖全静态合并 |
| 10 | Vulkan 分支 Actions 上编译 35min 卡死（7x 慢） | CPU 底包先出，GPU 后议 |

### 🔥 JNI / llama.cpp 推理坑（最痛的一区）

| # | 坑 | 解法 |
|---|---|---|
| 1 | n_batch>1 在 arm64 直接 SIGABRT | `SAFE_BATCH = 1`，llama_batch_init(1,0,1) 永远安全 |
| 2 | llama.cpp 内部把 n_batch=1 覆盖成 64 → 一次喂 64 token 触发 assertion | 完全不用 state->n_batch，硬编码 1 |
| 3 | KV cache 残留 → 第二次发消息吐不出字 | nativeChat 开头 `llama_kv_cache_clear(ctx)` |
| 4 | BOS 缺失 → 模型"困惑"发散成乱码 | add_spec=0 + 手动 insert BOS |
| 5 | 只判 eos(`<|endoftext|>`)，ChatML 实际输出 `<|im_end|>` → 硬写满 1024 token | 探测 im_end_id，命中立即 break |
| 6 | prefill 最后 token 和 generate 每 token 没设 logits=1 → 采样全错 | 每个位置都置 1 |
| 7 | llama_tokenize 返回负值当错误处理（-1214 报错截图） | 负值=所需 buffer 大小，取 abs() |
| 8 | b5180 vocab API 改名 + batch_init 参数顺序搞反 → seq_max=0 触发 GGML_ASSERT | llama_vocab_* 新命名 + (n_tokens, embd=0, n_seq_max=1) |
| 9 | 真推理在主线程跑 → 5s ANR | callbackFlow 内 launch(Dispatchers.Default)，双层保险 |
| 10 | collectLatest 丢 token → 空气泡（用户 3 次空回复截图的根因） | 改 collect |
| 11 | 内存预检 4GB 误杀魅族20 / 荣耀平板 | 阈值降 3GB/2GB + 4 级自动降级（4096→2048→1280→768） |
| 12 | 加载失败原因不可见 | nativeInit 两阶段 ThrowNew，带模型大小/可用内存/n_ctx/线程数回 Java |
| 13 | 采样参数过严抑制中文输出 | top_k=10 top_p=0.8 temp=0.3，freq/presence 惩罚归零 |

### 🎨 UI / Compose 坑

| # | 坑 | 解法 |
|---|---|---|
| 1 | LazyColumn 嵌套同方向可滚动父容器 → IllegalStateException 闪退 | 去掉外层 verticalScroll |
| 2 | 内容出屏幕被静默裁剪（诊断卡看不到） | 补 verticalScroll |
| 3 | LazyColumn key 重复闪退 | UUID 消息 ID + itemsIndexed key 拼 idx |
| 4 | scrollToItem 竞态 "entered drag with non-zero pending scroll" | 移除程序化滚动，靠 reverseLayout 贴底 |
| 5 | remember(allModels) 快照永远不重算 → 错误信息停在初始空 | 每次重组直接读原子值 |
| 6 | Modifier.clip 包名写错（foundation.clip 不存在） | androidx.compose.ui.draw.clip |

### 🤖 无障碍操控坑

| # | 坑 | 解法 |
|---|---|---|
| 1 | **微信风控**：频繁自动化操作 → 用户账号被限制成"新号" | QQ/微信全面禁用自动化（BLOCKED_PKGS） |
| 2 | App 克隆/分身对话框弹出两个图标 | handleAppChooser 过滤"分身"选主应用 |
| 3 | getRootInActiveWindow 微信 dump 为空 | getWindows 遍历兜底 |
| 4 | 微信找不到输入框 | 坐标聚焦 + 剪贴板粘贴兜底 |
| 5 | 快手/抖音搜不到 | 包名映射 + 右上角搜索图标坐标兜底 |
| 6 | Android 11+ 包可见性 | manifest queries 声明 |

### 🐙 GitHub 插件坑

| # | 坑 | 解法 |
|---|---|---|
| 1 | `@github 看状态` 被 LLM 当搜索词 → 跳浏览器 | 插件指令跳过 ACTION hint + onPreSend 返回空跳过 LLM |
| 2 | Token 带换行符 → Unexpected char 0x0a | trim 换行符 |
| 3 | Token 混入中文字符 → Unexpected char 0x7528 | 过滤所有非 ASCII |
| 4 | 用户粘贴格式乱（"GHP _ rdlw 0 mkrb..."） | 自动提取 ghp_ 前缀 + 格式校验（长度≥40） |
| 5 | Repo 填法五花八门 | 自动拆 owner/repo，设置页无效 token 自动清空 |
| 6 | 401 Bad credentials 无头绪 | 404 显示 URL+响应体诊断 |
| 7 | **构建产物是旧代码**（main 停在 code62） | 触发构建分支改 dev，提交也直推 dev |

---

## 五、核心代码地图

```
app/src/main/
├── cpp/
│   ├── llama_jni.cpp              # JNI 桥：nativeInit/nativeChat/nativeCancel
│   │                            #   SAFE_BATCH=1 / 手动 BOS / KV clear / im_end 探测
│   └── CMakeLists.txt           #   llama+ggml 全静态链接，强制 -O3
├── java/com/xuedi/coder/
│   ├── model/
│   │   ├── LlamaJniEngine.kt    # chatFlow callbackFlow 封装流式输出
│   │   │                        #   插件注册表 / 插件命中跳过 LLM / 4级降级加载
│   │   └── ChatPlugin.kt        # 插件接口（onPreSend 返回 "" = 跳过 LLM）
│   ├── plugin/
│   │   ├── GitHubPlugin.kt      # ⭐ code100 核心：@github 提交代码(Contents API 直推 dev)
│   │   │                        #   /触发构建/看状态/下载APK/最近runs
│   │   ├── GitHubTokenStore.kt  # Token 清洗（非ASCII过滤/ghp_提取/格式校验）
│   │   ├── WebSearchPlugin.kt   # @搜索：SearXNG 7实例 + wttr.in 天气 + Bing/百度/头条/神马兜底
│   │   └── ToolExecutionPlugin.kt
│   ├── action/
│   │   ├── ActionExecutor.kt          # <ACTION...> 标签解析执行
│   │   │                              #   BLOCKED_PKGS: QQ/微信一律拦截
│   │   └── CoderAccessibilityService.kt # 无障碍：open_app/搜索/发消息/点坐标
│   │                                  #   handleAppChooser 克隆对话框选主应用
│   ├── vm/
│   │   └── ChatViewModel.kt     # sendMessage 主流程 / ACTION_DYNAMIC_HINT 注入
│   │                            #   插件结果 updateLatestAssistantMsg 直显
│   ├── ui/screen/
│   │   ├── ChatPage.kt          # LazyColumn reverseLayout 贴底 / UUID key 防重复
│   │   └── SettingsPage.kt      # GitHub 配置 / 引擎开关 / 诊断卡 / 一键抓日志
│   └── data/                    # Room：会话/消息/模型 6 个 Entity
└── .github/workflows/build.yml  # NDK 26.1 安装 → assembleDebug → artifact + release
```

### 关键代码片段

**插件跳过 LLM 的契约**（LlamaJniEngine.chatFlow）：
```kotlin
val pluginResult = plugins.firstNotNullOfOrNull { it.onPreSend(input) }
// onPreSend 返回 "" → 插件已自行处理（如 @github），直接结束，不走 LLM
// 防止 "@github 看状态" 被 LLM 当搜索词跳浏览器
```

**手机直推 GitHub**（GitHubPlugin.commitFile，code100）：
```kotlin
// PUT /repos/{owner}/{repo}/contents/{path}
// branch=dev，Base64 编码内容，已有文件带 sha 覆盖
// push 即触发 Actions 构建 → 用户在 App 里 @github 下载APK 拿新包
```

**SAFE_BATCH 铁律**（llama_jni.cpp）：
```cpp
constexpr int SAFE_BATCH = 1;  // llama.cpp 会内部覆盖 n_batch，
// llama_batch_init(1,0,1) + batch.n_tokens=1 永远安全，魅族20 实测
```

---

## 六、当前状态与下一步

- ✅ code100 全部改动完成（14 文件，+1404/-200 行）
- ⏳ 本地构建验证 + 推送 dev 触发 Actions 出包
- 📱 用户拿到 code100 后的闭环：
  1. 设置页填 GitHub Token（ghp_ 开头，repo+workflow 权限）
  2. 聊天框发：`@github 提交 app/src/main/java/.../Foo.kt` + 换行粘贴代码
  3. 等几分钟，`@github 下载APK` 拿新包

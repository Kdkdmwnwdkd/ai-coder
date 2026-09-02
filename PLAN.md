# Xuedi Coder AI APP — 完整开发计划（PLAN.md）

> 本文档是给执行 AI 的完整指令。用户在另一个对话开启时，直接把全文粘贴过去即可开始工作。
> 仓库地址: https://github.com/Kdkdmwnwdkd/ai-coder
> 当前最新 tag: **v1.3.25-beta**（fix3，CI 通过，APK 已出，待用户验证）
> 目标平台: Android arm64-v8a，minSdk 26，targetSdk 34
> 模型: Qwen2.5-1.5B-Instruct Q4_K_M（当前），未来 3B Q4_K_M（一套代码自动适配）

---

## 一、当前状态（v1.3.25-beta fix3）

### 1.1 已完成：自写 forward pass 全部就位

```
app/src/main/cpp/
├── qwen_forward.cpp    ✅ 新建，~500 行纯数学 forward pass（无 ggml 依赖）
├── qwen_forward.h      ✅ 新建，对外接口
├── ggml_loader.cpp     ✅ 修了 Bug#5(static) + GGUF tensor info 解析 + ggml_type_size(Q4_K_M)
├── qwen_infer.cpp      ✅ 重写，forward_step 调 qwen_forward 接口，删掉所有 ggml hack
├── qwen_infer.h        ✅ 保留，数据结构定义
├── qwen_jni.cpp        ✅ JNI 桥接，不动
├── CMakeLists.txt      ✅ qwen-jni target，不链接 ggml/ggml-cpu/ggml-base
└── llama.cpp/          第三方源码（已不编进 libqwen-jni.so）
```

### 1.2 踩过的 8 个坑（全修了）

| # | Bug | 位置 | 根因 | 修复 |
|---|-----|------|------|------|
| 1 | ggml_tensor data 字段硬编码偏移 0x50/8 | make_weight_view() | ggml b5180 data 在 offset 176，不是 8/80 | **彻底放弃 ggml**，forward 全自写 |
| 2 | ggml_new_tensor no_alloc 下 data=NULL | ggml 内部 | ggml_new_tensor_impl no_alloc 不分配 data | 同上，不用 ggml 了 |
| 3 | Attention 全跳过 | qwen_infer.cpp 第 424 行 | 占位代码 `auto * attn_out = ln1` | **qwen_forward.cpp 真实现 GQA Attention** |
| 4 | KV cache 只写 pos 没写值 | qwen_infer.cpp 第 532 行 | 占位代码 `kv_pos = pos + 1` | **write_kv_cache() 真写 FP16** |
| 5 | static s_first/s_base 残留 | ggml_loader.cpp | 第二次 load 用第一次的 base | **去掉 static，直接算绝对偏移** |
| 6 | Q4_K_M block 大小算成 262B | qwen_forward.cpp v1 | 以为 scales 128B，实际 12B | **block=144B (2+2+12+128)，照搬 llama.cpp get_scale_min_k4** |
| 7 | GGUF tensor info ne[d]/offset 用 LEB128 读 | ggml_loader.cpp | GGUF v3 tensor info 用固定 uint64，不是 ULEB128 | **r.r<uint64_t>() 替换 r.vu64()** |
| 8 | ggml_type_size(Q4_K_M) 算成 256 | ggml_loader.cpp | 以为 scales 128B，实际 12B | **改返回 144** |

### 1.3 三个 fix 的 commit

| Commit | 修了什么 |
|--------|---------|
| 03fc7c4 | fix1: Q4_K_M 反量化照搬 llama.cpp b5180（block 144B, fp16 d/dmin, get_scale_min_k4） |
| fe3faff | fix2: GGUF tensor info ne[d]/offset 从 vu64()(LEB128) 改 r<uint64_t>()(fixed) |
| 2b9f7b0 | fix3: ggml_type_size(Q4_K_M) 从 256 改成 144（scales 是 12B 不是 128B） |

### 1.4 测试结果

- ✅ CI 编译通过（Debug + Release）
- ✅ APK 大小 15.9 MB，arm64-v8a
- ✅ libqwen-jni.so 不再链接 ggml/llama.cpp（100% 自写）
- ❓ 用户还没验证 forward 输出是否正常（fix3 刚出）

### 1.5 可能还存在的问题（如果 fix3 还是乱码）

如果 fix3 APK 还是乱码，问题在 forward 算子本身：
1. matmul 的行/列顺序反了（行主序 vs 列主序）
2. RoPE 旋转方向反了（cos/sin 对调）
3. Attention 的 mask 或 GQA repeat 逻辑错了
4. lm_head 应该 tie token_embd.weight 但 transpose 逻辑错了
5. dequant_tensor 对 F16/F32 权重的处理路径有 bug

**排查方法**：在 qwen_forward.cpp 每个算子前后加 LOG，打印输入输出的前几个值，看哪步开始变垃圾。

---

## 二、整体架构（六层，自己写五层）

```
┌─────────────────────────────────────────────────┐
│ 第6层: UI 界面（Jetpack Compose，借的）          │
│ 对话页 / 设置页 / 插件卡片 / 模型管理            │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 第5层: 插件路由（自己写，Kotlin）                 │
│ PluginRouter + Plugin 接口，6+ 插件注册/卸载       │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 第4层: 联网与工具层（自己写，Kotlin）             │
│ SearchEngine / GitHubEngine / WebFetchEngine /   │
│ TermuxEngine                                     │
└────────────────────┬────────────────────────────┘
                     │ 拿回来的全是文本
┌────────────────────▼────────────────────────────┐
│ 第3层: 本地推理引擎（自己写，C++） ✅ 已完成      │
│ ggml_loader.cpp (GGUF+mmap+BPE)                  │
│ qwen_forward.cpp (RMSNorm/matmul/RoPE/Attn/      │
│                  SwiGLU/Q4_K_M反量化/Sampler)     │
│ qwen_infer.cpp (generate循环+Session)            │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 第2层: JNI 桥接（自己写，C++ + Kotlin） ✅ 已完成 │
│ nativeLoadModel / nativeGenerate / nativeRelease │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 第1层: Android NDK + libc（借的）                │
│ mmap / open / close / pthread                    │
└─────────────────────────────────────────────────┘
```

**关键：第3层推理引擎，一行第三方推理框架代码都没有。** 没有 ggml、没有 llama.cpp、没有 onnxruntime。就是 C++ for 循环 + 数学公式。

**ggml_loader.cpp 里的 ggml_type_size() / ggml_blck_size() 是我们自己写的 static 查表函数**，不是 ggml 库的。C++ 代码零 ggml 依赖。

---

## 三、六个插件（当前），开放式插件系统

### 3.1 插件接口设计

```kotlin
interface Plugin {
    val id: String
    val name: String
    val description: String
    suspend fun handle(userInput: String): PluginResult
    fun settingsScreen(): Composable? = null
}

class PluginCapabilities(
    val textEngine: TextEngine,      // 本地推理（就是我们写的 forward）
    val searchEngine: SearchEngine,   // 联网搜索
    val githubEngine: GitHubEngine,   // GitHub API
    val execEngine: ExecEngine,       // Termux 执行
    val webFetch: WebFetchEngine      // 抓网页
)
```

### 3.2 六个插件分别挂在哪

| 插件 | 用什么能力 | 推理引擎 | 状态 |
|------|-----------|---------|------|
| ① 文本聊天 | textEngine | 本地自写 forward | ✅ 地基，已完成 |
| ② 生成照片 | textEngine + TermuxEngine | textEngine（写 prompt）+ Termux 跑 SD | 🟡 后干 |
| ③ 执行插件 | textEngine + TermuxEngine | textEngine（写代码） | 🟡 后干 |
| ④ 编辑插件 | textEngine + GitHubEngine | textEngine（改代码） | 🟡 后干 |
| ⑤ GitHub 模式 | textEngine + GitHubEngine | textEngine（总结搜索结果） | 🟡 后干 |
| ⑥ 联网模式 | textEngine + SearchEngine + WebFetchEngine | textEngine | 🟡 后干 |

### 3.3 插件增删

- 内置插件默认 register，Settings 里开关控制
- 加插件 = `router.register(newPlugin)`
- 删插件 = `router.unregister(id)`
- **地基一行代码不动**

---

## 四、之后要干的事（优先级排序）

### 🔴 优先级 0: 验证 fix3 APK 的 forward 输出是否正常

**用户装 fix3 APK 后：**
1. Settings → 开 Qwen 引擎开关
2. 模型管理 → 点"重新加载到内存"
3. 看 modelLoaded 是否变成 true
4. 去对话页问"你好"
5. 看 logcat 里 qwen-jni 的日志

**如果还是乱码/崩溃：**
- 加 debug log 到 qwen_forward.cpp 每个算子前后
- 打印 embeddings 前几个值、RMSNorm 输出前几个值、matmul 输出前几个值
- 定位哪步开始变垃圾
- 可能的问题点：matmul 顺序、RoPE 方向、Attention GQA、lm_head transpose

**如果能输出正常中文：**
- 🎉 地基跑通了！
- 开始考虑删 Llama 引擎（等 Qwen 完全稳定）

### 🔴 优先级 1: 删 Llama 引擎（如果 Qwen 稳定）

要删的东西：
```
app/src/main/cpp/
├── llama.cpp/                    删除整个目录
├── llama_jni.cpp                 删除
├── CMakeLists.txt                去掉 llama-jni target, 去掉 llama.cpp add_subdirectory
├── build.gradle.kts              去掉 externalNativeBuild 的 llama target

app/src/main/java/
├── model/LlamaJniEngine.kt       删除
├── App.kt                        去掉 llamaEngine / llmEngine 双路分发, 直接用 qwenEngine
└── ui/screen/SettingsPage.kt     去掉引擎切换开关
```

删完 APK 体积会小一大块（libxuedi-llama.so 比 libqwen-jni.so 大）。

### 🟡 优先级 2: 联网搜索（SearchEngine）

- DuckDuckGo lite 版: `https://lite.duckduckgo.com/lite/?q=xxx`
- 返回干净 HTML，解析搜索结果摘要
- 搜回来的文本拼进 prompt 的 context
- Kotlin 侧实现，不碰 C++

### 🟡 优先级 3: GitHub 能力（GitHubEngine）

- 匿名读: REST API `/repos/{owner}/{repo}` 拿仓库信息、读文件、列 issue
- 需要 token 写: 用户手动填 GitHub Personal Token
- API 基础路径: `https://api.github.com`
- 国内 fallback: 直连失败换 ghproxy

### 🟡 优先级 4: 代码执行（TermuxEngine）

- 检测 Termux 是否安装
- 调 Termux 执行 Python/Node.js
- 代码由文本插件（就是我们的推理引擎）生成
- stdout/stderr 回传聊天窗口

### 🟡 优先级 5: 图片生成

- Termux + python + diffusers 跑 SD 1.5 量化版
- prompt 由文本插件生成
- 先能跑就行，不追求质量

### 🟢 优先级 6: NEON 优化

- naive matmul 跑通后，写 ARM NEON SIMD 加速
- Q4_K_M 反量化可以 NEON 并行
- 这是提速，不是正确性，放最后

### 🟢 优先级 7: 云端 API 可选开关

- 用户手动开的开关，默认关
- 支持 OpenRouter / 硅基流动 / 自搭服务
- 三种模式共存: 离线 / 联网搜索+本地推理 / 云端

---

## 五、快速参考

### 仓库信息
- URL: https://github.com/Kdkdmwnwdkd/ai-coder
- 主要分支: main
- 当前 tag: v1.3.25-beta（force push 过，commit 2b9f7b0）

### APK 下载链接
- **fix3**: https://github.com/Kdkdmwnwdkd/ai-coder/releases/download/v1.3.25-beta/xuedi-coder-v1.3.25-fix3-arm64-v8a.apk
- Release 页: https://github.com/Kdkdmwnwdkd/ai-coder/releases/tag/v1.3.25-beta

### 版本号
- versionCode: 36
- versionName: 1.3.25-beta

### CI 工作流
- 文件: `.github/workflows/build.yml`
- tag `v*` 自动触发 Release 构建
- 构建约 4-5 分钟
- APK 挂在 Release 的 assets 里
- APK 文件名统一: `xuedi-coder-v{版本}-beta-arm64-v8a.apk`（纯 ASCII，不要中文）

### 测试设备
- 魅族 20，骁龙 8 Gen 2
- Android，arm64-v8a
- 已验证 1.5B Q4_K_M GGUF 文件在设备上（940MB）

### GGUF 文件（用户设备上的）
- 路径: `/data/data/com.xuedi.coder/files/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf`
- 大小: 940MB
- arch: qwen2, n_layer=28, n_embd=1536, n_head=12, n_head_kv=2, n_ff=8960, head_dim=128
- vocab: 151646 tokens (byte-level BPE, special tokens included)

### Q4_K_M block 格式（已验证正确）
```
每个 256 元素一组 = 144 bytes:
  offset 0:   ggml_half d       (2B, super-block scale, fp16)
  offset 2:   ggml_half dmin    (2B, super-block min scale, fp16)
  offset 4:   uint8  scales[12]  (12B, 6-bit 量化的 scale/min 对, K_SCALE_SIZE=12)
  offset 16:  uint8  qs[128]    (128B, 4bit 数据: 低 4bit=elem[i*2], 高 4bit=elem[i*2+1])

反量化 (照搬 llama.cpp b5180 get_scale_min_k4 + dequantize_row_q4_K):
  每 64 元素一组, 用 get_scale_min_k4 从 scales[12] 解出 2 组 (sc, m)
  get_scale_min_k4(j, scales, &sc, &m):
    if j < 4:  sc = scales[j] & 63;   m = scales[j+4] & 63;
    else:      sc = (scales[j+4]&0xF) | ((scales[j-4]>>6)<<4);
               m  = (scales[j+4]>>4)  | ((scales[j-0]>>6)<<4);
  d1 = d * sc; m1 = min * m     (sc/m 是 6-bit 整数 0..63)
  前 32: y[l] = d1 * (q[l] & 0xF) - m1
  后 32: y[l] = d2 * (q[l] >> 4)  - m2

block 总大小: 2 + 2 + 12 + 128 = 144 bytes (不是 256 也不是 262!)
ggml_type_size(Q4_K_M) 应返回 144
ggml_blck_size(Q4_K_M) 应返回 256 (每 256 元素一组)
```

### GGUF v3 tensor info 格式（已验证正确）
```
name: ULEB128 len + bytes        ← LEB128 变长
n_dims: uint32 (4B fixed)        ← 固定大小, 不是 LEB128!
dims[n_dims]: uint64 each (8B)   ← 固定大小, 不是 LEB128! (之前错用 vu64())
dtype: uint32 (4B fixed)
offset: uint64 (8B fixed)        ← 固定大小, 不是 LEB128! (之前错用 vu64())
```

### RoPE NeoX 公式
```
inv_freq[i] = exp(-2 * i * log(freq_base) / head_dim)   // i = 0..head_dim/2-1
// 对 q[k] 的 [2i, 2i+1] 两维:
cos_val = cos(pos * inv_freq[i])
sin_val = sin(pos * inv_freq[i])
new_q[2i]   = q[2i] * cos_val - q[2i+1] * sin_val
new_q[2i+1] = q[2i] * sin_val + q[2i+1] * cos_val
k 同理
Qwen2 freq_base = 1000000
```

### Qwen2 SwiGLU
```
gate = X @ W_gate    // [1, n_embd] @ [n_embd, n_ff] → [1, n_ff]
up   = X @ W_up
hidden = silu(gate) ⊙ up   // 逐元素乘, silu(x) = x * sigmoid(x)
out = hidden @ W_down   // [1, n_ff] @ [n_ff, n_embd] → [1, n_embd]
```

### GQA Attention
```
Q: [n_head, head_dim]        — 每个 head 一行, 共 n_head 行
K: [n_head_kv, head_dim]     — 只有 kv head 数
V: [n_head_kv, head_dim]
rep = n_head / n_head_kv     // 12/2 = 6

对每个 Q head h:
  kv_h = h / rep
  scores[t] = Q[h] · K_cache[kv_h, t] / sqrt(head_dim)   // 对所有历史位置
  scores = softmax(scores)
  out[h] = Σ_t scores[t] * V_cache[kv_h, t]
```

---

## 六、安全和上下文管理

### 6.1 敏感信息泄露风险

**仓库 Remote URL 里有 GitHub Personal Access Token！**
- 当前 remote: `https://github.com/Kdkdmwnwdkd/ai-coder.git`（token 在本地 git config）
- **绝对不要在对话历史里粘贴 token 值！**
- 如果 token 泄露，去 GitHub → Settings → Developer settings → Personal access tokens → 立刻 revoke

### 6.2 每个新对话的标准启动流程

```
1. 用户把 PLAN.md 全文粘贴给执行 AI
2. 执行 AI 快速过一遍 PLAN.md，确认理解
3. 执行 AI 直接跳到当前状态（fix3 已推，等用户验证）
4. 如果 fix3 已出正常中文 → 推进优先级 1（删 Llama 引擎）
5. 如果 fix3 还是乱码 → 在 qwen_forward.cpp 加 debug log，定位问题
6. 不要重新读源文件，不要重新查 ggml 源码，不要重新 git log
```

### 6.3 如果遇到 PLAN.md 里没有的问题

- 先在 PLAN.md 里更新这部分内容
- 然后写代码
- 下次新对话就不用再重新发现这个问题了

---

## 七、给执行 AI 的注意事项

1. **积分省着用**：每一步都写好再 commit，避免多次来回
2. **push 前检查**：`git diff --stat` 确认只改了预期文件；`git log --oneline -1` 确认没有 token
3. **不要引入新依赖**：推理引擎一行第三方推理框架都不要加
4. **forward 里绝对不能硬编码数字**：12、28、1536 这些全是 cfg 里的值
5. **APK 文件名保持 ASCII**：`xuedi-coder-v{版本}-fix{N}-arm64-v8a.apk`
6. **遇到 PLAN.md 里没有的问题**：先更新 PLAN.md，再写代码
7. **绝对禁止**：改 LlamaJniEngine.kt（除非确认要删）、加新的第三方推理框架依赖
8. **所有张量偏移/反量化格式都照搬 llama.cpp b5180 源码**：已经验证过正确，不要再自己发明

---

**报告结束。**

# Xuedi Coder AI APP — 完整开发计划（PLAN.md）

> 本文档是给执行 AI 的完整指令。用户在另一个对话开启时，直接把全文粘贴过去即可开始工作。
> 仓库地址: https://github.com/Kdkdmwnwdkd/ai-coder
> 当前最新 tag: v1.3.24-beta（有 bug，需要重构 forward pass）
> 目标平台: Android arm64-v8a，minSdk 26，targetSdk 34
> 模型: Qwen2.5-1.5B-Instruct Q4_K_M（当前），未来 3B Q4_K_M（一套代码自动适配）

---

## 一、现状（v1.3.24-beta）和问题根因

### 1.1 已有代码文件

```
app/src/main/cpp/
├── ggml_loader.cpp      ✅ GGUF v3 解析 + mmap 权重 + BPE tokenizer（基本正确）
├── qwen_infer.h         ✅ 数据结构定义 + API（需要微调）
├── qwen_jni.cpp         ✅ JNI 桥接（正确，不用改）
├── qwen_infer.cpp       ❌ forward pass 用 ggml 图，有 5 个致命 bug
├── CMakeLists.txt       ✅ qwen-jni target 定义（需要去掉 ggml 依赖）
└── llama.cpp/           第三方源码（v1.3.24 编了 libqwen-jni.so 但 forward 用不上了）

app/src/main/java/com/xuedi/coder/
├── model/QwenInferEngine.kt   ✅ Kotlin 侧引擎接口（基本正确，不用改）
├── model/ModelManager.kt      ✅ 模型管理（基本正确）
├── App.kt                     ✅ 双引擎分发（基本正确）
└── ui/screen/SettingsPage.kt  ✅ 设置页 UI（基本正确）
```

### 1.2 qwen_infer.cpp 的 5 个致命 bug（为什么开启 Qwen 推理器就加载失败）

**Bug #1: ggml_tensor data 字段偏移硬编码错误**
- 位置: `make_weight_view()` 第 122 行: `*(void**)((char*)g + 0x50 /* data 字段偏移 */) = t->data;`
- 位置: `make_weight_view_safe()` 第 169 行: `*(void**)(base + 8) = t->data;`
- 真实 ggml b5180 结构体中 `data` 在 offset **176**（不是 8 也不是 80）
- 后果: mmap 权重指针写到了错误字段，tensor->data 还是 NULL

**Bug #2: ggml_new_tensor 在 no_alloc 模式下 data 就是 NULL**
- ggml_new_tensor_impl 在 no_alloc 时，data 不分配也不设 view_src，直接是 NULL
- 我们在 no_alloc 下 new_tensor 后想手动补 data，但 Bug#1 的偏移又错了

**Bug #3: Attention 完全没实现**
- 第 424 行: `auto * attn_out = ln1; // 占位`
- Q*K^T、softmax、*V 三步全跳过了
- 没有 self-attention 能力，模型不具备 transformer 最核心机制

**Bug #4: KV cache 没写入**
- 第 532-535 行: 只写了 kv_pos++，没写实际的 K/V 数据
- 每次 forward 都在"假装"有历史

**Bug #5: static 变量残留导致多次 load 出错**
- ggml_loader.cpp 第 418-424 行: `static bool s_first = true; static uint64_t s_base = 0;`
- 第二次 load 模型会用第一次算的 s_base，tensor offset 全错

### 1.3 结论

**不是 GGUF 文件坏了，不是 BPE tokenizer 错了，不是 JNI 桥接有问题。** 就是 forward pass 里那堆 ggml 黑盒 hack 烂了。

**修复策略：彻底放弃 ggml 图，forward pass 全部自己写。**

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
│ 第3层: 本地推理引擎（自己写，C++）               │  ← 明天开始干
│ GGUF Loader + BPE Tokenizer + Forward Pass +     │
│ Q4_K_M Decoder + Sampler                         │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 第2层: JNI 桥接（自己写，C++ + Kotlin）          │
│ nativeLoadModel / nativeGenerate / nativeRelease │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────┐
│ 第1层: Android NDK + libc（借的）                │
│ mmap / open / close / pthread                    │
└─────────────────────────────────────────────────┘
```

**关键：第3层推理引擎，一行第三方推理框架代码都没有。** 没有 ggml、没有 llama.cpp、没有 onnxruntime。就是 C++ for 循环 + 数学公式。

---

## 三、六个插件（当前），开放式插件系统

### 3.1 插件接口设计

```kotlin
interface Plugin {
    val id: String           // 唯一标识
    val name: String         // 显示名
    val description: String  // 给用户看的说明
    suspend fun handle(userInput: String): PluginResult
    fun settingsScreen(): Composable? = null
}

class PluginCapabilities(
    val textEngine: TextEngine,      // 本地推理
    val searchEngine: SearchEngine,   // 联网搜索
    val githubEngine: GitHubEngine,   // GitHub API
    val execEngine: ExecEngine,       // Termux 执行
    val webFetch: WebFetchEngine      // 抓网页
)
```

### 3.2 六个插件分别挂在哪

| 插件 | 用什么能力 | 推理引擎 | 状态 |
|------|-----------|---------|------|
| ① 文本聊天 | textEngine | 本地自写 forward | 🔴 地基，先干 |
| ② 生成照片 | textEngine + TermuxEngine | 本地自写 forward（写 prompt）+ Termux 跑 SD | 🟡 后干 |
| ③ 执行插件 | textEngine + TermuxEngine | 本地自写 forward（写代码） | 🟡 后干 |
| ④ 编辑插件 | textEngine + GitHubEngine | 本地自写 forward（改代码） | 🟡 后干 |
| ⑤ GitHub 模式 | textEngine + GitHubEngine | 本地自写 forward（总结搜索结果） | 🟡 后干 |
| ⑥ 联网模式 | textEngine + SearchEngine + WebFetchEngine | 本地自写 forward | 🟡 后干 |

### 3.3 插件增删

- 内置插件默认 register，Settings 里开关控制
- 加插件 = `router.register(newPlugin)`
- 删插件 = `router.unregister(id)`
- **地基一行代码不动**

---

## 四、第3层推理引擎：完整规格

### 4.1 文件结构（改完后）

```
app/src/main/cpp/
├── ggml_loader.cpp      ✅ 保留，修 Bug#5（去掉 static 变量）
├── qwen_infer.h         ✅ 保留，微调（forward_step 改签名）
├── qwen_jni.cpp         ✅ 保留，不动
├── qwen_forward.cpp     🆕 新建，纯数学实现的 forward pass
├── qwen_infer.cpp       🟡 重写，forward_step 调 qwen_forward.cpp 的函数
└── CMakeLists.txt       🟡 改：加 qwen_forward.cpp，去掉 ggml/ggml-cpu/ggml-base 链接
```

### 4.2 qwen_forward.cpp 要实现的东西

```cpp
// ===== 1. 算子 =====

// RMSNorm: out[i] = x[i] / sqrt(mean(x^2) + eps) * w[i]
// 纯数学，10 行
void rms_norm(float* out, const float* x, const float* w, int n, float eps);

// SiLU: out[i] = x[i] * sigmoid(x[i])
// 5 行
void silu(float* out, const float* x, int n);

// SwiGLU: out = silu(gate @ x) * (up @ x)，再 down @ out
// 3 次 matmul + silu + mul
void swiglu(float* out, const float* x, 
            const float* w_gate, const float* w_up, const float* w_down,
            int n_embd, int n_ff);

// RoPE NeoX 风格: 对 q 和 k 的前 head_dim 维做旋转
// Qwen2 用 freq_base=1000000
void rope(float* q, float* k, int pos, int head_dim, float freq_base);

// ===== 2. Q4_K_M 反量化 =====
// block 格式 (256 元素一组):
//   2 bytes: d_min (int16)
//   2 bytes: d (int16)  
//   128 bytes: scales[128]  (每 2 元素共享一个 scale)
//   128 bytes: data[128]    (4 bit/elem → 256 elem)
void dequant_q4km(float* out, const uint8_t* block_ptr, int n);

// ===== 3. Matmul =====
// 先 naive 版: C[m×n] = A[m×k] @ B[k×n]
// 三层 for 循环，跑通后再加 NEON 优化
// 注意 A[m×k] 每个 K 行可能是 Q4_K_M 量化的 → 每行先反量化成临时 float
void matmul_naive(float* C, const float* A, const float* B, int m, int k, int n);
// A 是 Q4_K_M 量化张量版本: 每行列先反量化再乘
void matmul_q4km(float* C, const uint8_t* A_data, int a_ne0, int a_ne1,
                 const float* B, int k, int n);

// ===== 4. Attention =====
// 完整 GQA Attention:
//   Q = [head_dim, n_head]
//   K = [head_dim, n_head_kv, pos+1]
//   V = [head_dim, n_head_kv, pos+1]
//   scores = softmax(Q^T @ K / sqrt(head_dim))
//   out = V @ scores
// GQA: 把 K,V 的每个 kv head 重复 rep=n_head/n_head_kv 次
void attention(float* out, 
               float* Q, float* K, float* V,
               float* k_cache, float* v_cache, int pos,
               int n_head, int n_head_kv, int head_dim);

// ===== 5. Transformer 层 =====
// x = x + attn(RMSNorm(x) @ Q/K/V + RoPE + Attention + O_proj)
// x = x + SwiGLU(RMSNorm(x) @ gate/up, down)
void transformer_layer(float* x, int l, int pos, float* k_cache, float* v_cache);

// ===== 6. 顶层 forward =====
// 输入: token_id + pos
// 输出: logits (vocab_size 个 float)
// 流程: embeddings → 28 层 transformer → final norm → lm_head
int forward_step(int token_id, int pos, float* logits_out);
```

### 4.3 内存布局

```
权重: GGUF mmap 只读区 (Q4_K_M 量化格式，按 block 存)
  → matmul 时每行先反量化到临时 float buffer (栈上或 malloc)

KV cache: FP16, 每层 [max_seq_len, n_head_kv, head_dim]
  → 手机上 1.5B + 4K context ≈ 700MB, 3B + 4K ≈ 1.2GB

中间激活: float, 每层 [n_embd] 一个向量 (1536*4B ≈ 6KB)
  → 栈上分配
```

### 4.4 自适应：一套代码通吃 1.5B 和 3B

所有超参数从 `QwenModelConfig` 读（GGUF loader 已经在做）：
- `cfg.n_layer` → 循环层数
- `cfg.n_head` / `cfg.n_head_kv` → attention 的 head 数
- `cfg.n_embd` → 向量维度
- `cfg.n_ff` → SwiGLU 中间层
- `cfg.head_dim = n_embd / n_head`

**代码里绝对不能出现 12、28、1536、8960 这些硬编码数字。** 全部用 cfg 里的值。

### 4.5 CMakeLists.txt 要改什么

```cmake
# 之前: 链接 ggml, ggml-cpu, ggml-base
# 之后: 这些全去掉，只链接 log, z

add_library(qwen-jni SHARED
    qwen_infer.cpp
    qwen_forward.cpp    # 🆕 加这个
    ggml_loader.cpp
    qwen_jni.cpp
)

target_link_libraries(qwen-jni PRIVATE
    log
    z
    # 没有 ggml！没有 llama.cpp！
)
```

---

## 五、明天（或下一个对话）要干的事

### Step 1: 写 qwen_forward.cpp（最急，60-80 积分）

按上面 4.2 的规格，把所有算子写出来。重点：

1. **Q4_K_M 反量化格式必须正确**（先跑一个小脚本验证，或者直接写进去跑起来看输出）
2. **naive matmul 先跑通**，NEON 后面加
3. **Attention 必须完整实现**（之前是占位，这次要真的 Q*K^T softmax V）
4. **KV cache 必须真的写数据**（fp16 存进去，下一轮真的读出来）
5. **RoPE NeoX 风格**，freq_base 用 cfg.rope_freq_base（Qwen2 是 1000000）
6. **每个算子加简单的 debug log**（比如 rms_norm 输出 nan 就打出来）

### Step 2: 改 qwen_infer.cpp（30-40 积分）

删掉所有 ggml_tensor 构造、ggml_reset、ggml_new_graph、ggml_graph_compute 相关代码。
`forward_step` 改成调用 `qwen_forward.cpp` 里的函数。
采样逻辑（top-k / top-p / temperature）保留，那个没坏。

### Step 3: 改 ggml_loader.cpp Bug#5（5 积分）

`static bool s_first` 和 `static uint64_t s_base` 改成 `QwenModel` 类的成员变量，或者直接去掉——tensor offset 用绝对偏移就行，不需要算 base。

### Step 4: 改 CMakeLists.txt（10 积分）

加 `qwen_forward.cpp`，去掉 ggml 三个库的链接。

### Step 5: push + tag + 等 CI（20-40 积分 + 等待）

```bash
git add -A
git commit -m "v1.3.25-beta: 自写 forward pass, 彻底去掉 ggml 依赖"
git tag v1.3.25-beta
git push origin main --tags
```

等 GitHub Actions 跑完 5 分钟，Release 页会挂出 APK。

---

## 六、之后要干的事（地基稳了之后）

### 优先级 1: 联网搜索（SearchEngine）
- DuckDuckGo lite 版: `https://lite.duckduckgo.com/lite/?q=xxx`
- 返回干净 HTML，解析搜索结果摘要
- 搜回来的文本拼进 prompt 的 context
- Kotlin 侧实现，不碰 C++

### 优先级 2: GitHub 能力（GitHubEngine）
- 匿名读: REST API `/repos/{owner}/{repo}` 拿仓库信息、读文件、列 issue
- 需要 token 写: 用户手动填 GitHub Personal Token
- API 基础路径: `https://api.github.com`
- 国内 fallback: 直连失败换 ghproxy

### 优先级 3: 代码执行（TermuxEngine）
- 检测 Termux 是否安装
- 调 Termux 执行 Python/Node.js
- 代码由文本插件（就是我们的推理引擎）生成
- stdout/stderr 回传聊天窗口

### 优先级 4: 图片生成
- Termux + python + diffusers 跑 SD 1.5 量化版
- prompt 由文本插件生成
- 先能跑就行，不追求质量

### 优先级 5: NEON 优化
- naive matmul 跑通后，写 ARM NEON SIMD 加速
- Q4_K_M 反量化可以 NEON 并行
- 这是提速，不是正确性，放最后

### 优先级 6: 云端 API 可选开关
- 用户手动开的开关，默认关
- 支持 OpenRouter / 硅基流动 / 自搭服务
- 三种模式共存: 离线 / 联网搜索+本地推理 / 云端

---

## 七、给执行 AI 的注意事项

1. **积分省着用**：每一步都写好再 commit，避免多次来回
2. **代码写完整再 push**：不要 push 半成品然后在 CI 等结果发现又要改
3. **每个算子加 debug log**：方便用户在 logcat 里看哪步炸了
4. **不要引入新依赖**：推理引擎一行第三方推理框架都不要加
5. **forward 里绝对不能硬编码数字**：12、28、1536 这些全是 cfg 里的值
6. **push 前本地至少检查编译**：`cd app/src/main/cpp && mkdir -p build && cd build && cmake .. -DANDROID_STL=c++_static && cd ..`（如果沙箱有 cmake 的话）
7. **APK 文件名保持 ASCII**：`xuedi-coder-v1.3.25-beta-arm64-v8a.apk`，不要用中文

---

## 八、安全和上下文管理

### 8.1 敏感信息泄露风险（重要！）

**仓库 Remote URL 里有 GitHub Personal Access Token！**
- 当前 remote: `https://github.com/Kdkdmwnwdkd/ai-coder.git`（token 在本地 git config，不要在任何对话或文档里粘贴）
- 这个 token 直接写在 `.git/config` 里
- **绝对不要在对话历史里粘贴这个 token！**
- **绝对不要把这个 token 提交到任何公共文件或日志里！**
- 如果 token 泄露，去 GitHub → Settings → Developer settings → Personal access tokens → 立刻 revoke

**PLAN.md 里不应该包含 token**，但 git config 里有，执行 AI 跑 git 命令时可能会在输出里暴露。**任何 git 命令输出如果包含 remote URL，要把 token 部分打码。**

### 8.2 每个新对话都要重新开始的问题

当前每个新对话执行 AI 都要：
1. 重新读一遍所有 C++ 源文件（~1500 行）
2. 重新查 ggml 源码验证结构体偏移
3. 重新理解整个架构
4. 重新跑一堆 git 命令确认状态

**省积分的做法：**
- **所有上下文都在 PLAN.md 里**，新对话直接把 PLAN.md 贴进去就行
- **不需要重新读代码**，PLAN.md 里已经写了每个文件的状态和要改什么
- **不需要重新查 ggml 源码**，PLAN.md 里已经写了 Bug#1-5 的根因
- **执行 AI 的第一步应该是：读 PLAN.md → 直接跳到"五、要干的事"Step 1 开始写代码**
- **禁止在新对话里重复探索已经明确的问题**

### 8.3 每个新对话的标准启动流程

```
1. 用户把 PLAN.md 全文粘贴给执行 AI
2. 执行 AI 快速过一遍 PLAN.md，确认理解
3. 执行 AI 直接跳到"五、要干的事"，从 Step 1 开始
4. 不要重新读源文件，不要重新查 ggml 源码，不要重新 git log
5. 积分花在"写代码"上，不要花在"重新理解"上
```

### 8.4 如果遇到 PLAN.md 里没有的问题

- 先在 PLAN.md 里更新这部分内容
- 然后写代码
- 下次新对话就不用再重新发现这个问题了

---

## 九、快速参考

### 仓库信息
- URL: https://github.com/Kdkdmwnwdkd/ai-coder
- ⚠️ Remote 里有 token，但**不要在任何对话里粘贴 token 值**
- 主要分支: main

### 版本号
- 当前: v1.3.24-beta（tag 存在，有 bug）
- 下一个: v1.3.25-beta（tag 还没打，等 forward 写好）

### CI 工作流
- 文件: `.github/workflows/build.yml`
- tag `v*` 自动触发 Release 构建
- 构建约 5 分钟
- APK 挂在 Release 的 assets 里
- APK 文件名统一: `xuedi-coder-v{版本}-beta-arm64-v8a.apk`（纯 ASCII）

### 测试设备
- 魅族 20，骁龙 8 Gen 2
- Android，arm64-v8a
- 已验证 1.5B Q4_K_M GGUF 文件在设备上（940MB）

### GGUF 文件（用户设备上的）
- 路径: `/data/data/com.xuedi.coder/files/models/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf`
- 大小: 940MB
- arch: qwen2, n_layer=28, n_embd=1536, n_head=12, n_head_kv=2, n_ff=8960, head_dim=128
- vocab: 151646 tokens (byte-level BPE, special tokens included)

### Q4_K_M block 格式（写反量化时用）
```
每个 256 元素一组 = 262 bytes:
  offset 0:   int16 d_min   (2B)
  offset 2:   int16 d       (2B)  
  offset 4:   int8  scales[128]  (128B, 每 2 个元素共享一个 scale)
  offset 132: uint8 data[128]    (128B, 4bit/elem: 低 4bit = elem[i*2], 高 4bit = elem[i*2+1])

反量化:
  scale = d + (d_min - d) * scales[i] / 127.0   // 每 2 个元素共用
  value = scale * (nibble_value - 8)             // nibble_value = data byte 的低/高 4bit
```

### RoPE NeoX 公式（写 rope 时用）
```
inv_freq[i] = 1.0 / (freq_base ^ (i / head_dim))   // i = 0..head_dim/2-1
// 对 q[k] 的 [2i, 2i+1] 两维:
cos_val = cos(pos * inv_freq[i])
sin_val = sin(pos * inv_freq[i])
new_q[2i]   = q[2i] * cos_val - q[2i+1] * sin_val
new_q[2i+1] = q[2i] * sin_val + q[2i+1] * cos_val
k 同理
```

### Qwen2 SwiGLU（写 transformer 层时用）
```
gate = X @ W_gate    // [n_embd, 1] @ [n_embd, n_ff] → [n_ff, 1]
up   = X @ W_up      // [n_ff, 1]
hidden = silu(gate) ⊙ up   // 逐元素乘
out = hidden @ W_down   // [n_ff, 1] @ [n_ff, n_embd] → [n_embd, 1]
```

### GQA Attention（写 attention 时用）
```
Q: [head_dim, n_head]
K: [head_dim, n_head_kv, pos+1]
V: [head_dim, n_head_kv, pos+1]
rep = n_head / n_head_kv   // 12/2 = 6
// 每个 KV head 服务 rep 个 Q head:
// K[:, h*rep:(h+1)*rep, :] 都是同一个 K[:, h, :]
scores[h, :] = softmax(Q[:, h]^T @ repeat(K[:, h, :], rep) / sqrt(head_dim))
out[:, h] = repeat(V[:, h, :], rep) @ scores[h, :]
```

---

**报告结束。**

## 十、风险控制：没理解会不会写炸？

### 10.1 可能的"没理解"场景

| 场景 | 风险等级 | 后果 |
|------|---------|------|
| Q4_K_M 反量化公式写错 | 🔴 高 | 权重全是垃圾，推理输出乱码，但不会 crash |
| RoPE 公式写错 | 🟡 中 | 位置编码错位，输出质量差，但不会 crash |
| Attention 算错 | 🔴 高 | 没有 self-attention，输出不具备 transformer 能力 |
| 自作主张改了 ggml_loader.cpp 里不该改的 | 🔴 高 | GGUF 解析出错，加载直接失败 |
| 硬编码了 12/28/1536 这些数字 | 🟡 中 | 1.5B 能跑，3B 跑不了 |
| 遇到 PLAN.md 没提到的情况瞎猜 | 🔴 高 | 可能写出完全错误的代码 |
| push 时漏了某个文件 | 🟡 中 | CI 编译失败，但容易发现 |

### 10.2 每一步的硬验证（防止写炸了还 push）

**Step 1 写完 qwen_forward.cpp 后，必须做的自检：**

```
1. 每个算子加 static_assert 或注释标明公式来源（PLAN.md 第几节）
2. Q4_K_M 反量化：写一个小的单元测试 main()（或者直接在代码里加 #ifdef TEST 块）
   输入已知的 block bytes，输出应该是 256 个 float
   可以和 Python 脚本算出来的结果对比
3. RoPE：对 head_dim=4, pos=0 的简单 case，cos(0)=1, sin(0)=0，输出应该等于输入
4. 编译检查：沙箱里跑 g++ -c qwen_forward.cpp -I. 看有没有语法错误
```

**Step 5 push 前必须做：**

```
1. git diff HEAD~1 --stat 确认只改了预期的文件
   预期改动: qwen_forward.cpp (新建), qwen_infer.cpp, ggml_loader.cpp, CMakeLists.txt
   如果出现了 LlamaJniEngine.kt 或 llama.cpp 下的文件 → 说明 AI 跑题了，检查后再 push
2. git log --oneline -1 确认 commit message 里没有 token 或敏感信息
3. 如果沙箱有 cmake + Android NDK，尝试跑一次 cmake 配置看看能不能通过
```

**CI 构建结果是最终验证：**
- CI 能编译通过 → 至少语法、头文件、链接都对了
- CI 编译失败 → 直接看日志修，不用猜
- CI 过了但用户反馈还是崩 → 说明是运行时逻辑问题，看 logcat

### 10.3 安全网：小步提交

**不要一次性写完所有 Step 再 push。** 建议：

```
提交 1: 只改 ggml_loader.cpp Bug#5（5 行改动，风险最低）
提交 2: 新建 qwen_forward.cpp，只写 q4km_dequant + rms_norm + silu（3 个简单算子）
提交 3: 加 matmul_naive + rope
提交 4: 加 attention + transformer_layer
提交 5: 改 qwen_infer.cpp 的 forward_step
提交 6: 改 CMakeLists.txt
打 tag + 触发 CI
```

**这样每一步出错，都知道是哪一步引进的。** 而不是一次性写完 500 行再 push，错了全身上下都是嫌疑。

### 10.4 如果执行 AI 没理解，用户该怎么做

**触发信号：**
- AI 说"让我先重新读一下源文件" → 它没理解 PLAN.md，想自己探索
- AI 说"我去查一下 llama.cpp 源码确认 Q4_K_M 格式" → 它不信任 PLAN.md 里的公式
- AI 开始改 LlamaJniEngine.kt 或 llama.cpp 下的文件 → 它跑题了
- AI 说"我觉得应该加 xxx 依赖会更好" → 它想自作主张加东西

**用户该说：**
```
停止。回到 PLAN.md，只做 Step X。
不要重新读文件，不要查源码，不要改 C++ 推理层以外的文件。
PLAN.md 里写的公式就是标准答案，按那个写。
遇到 PLAN.md 里没有的问题，先在 PLAN.md 里加一节，然后告诉我。
```

### 10.5 最坏情况：写出的 forward 跑不通

**后果：** 用户装了 APK，开 Qwen 引擎，加载成功（因为 GGUF loader 没改），但 generate 输出全是乱码或崩溃。

**回滚方案：**
1. 用户在 Settings 里把开关切回 Llama 引擎（v1.3.16 稳定版路径）→ 立刻恢复能用
2. git revert 掉 v1.3.25-beta 的 commit → 重新打 tag → 旧代码生效
3. **Llama 引擎和 Qwen 引擎是两条独立路径**，Qwen 炸了不影响 Llama

**这就是为什么我们留了 Llama 引擎当 fallback。** Qwen 推理器 beta 期间，Llama 引擎永远是稳定退路。

---

## 给执行 AI 的最后指令

1. **新对话启动时**：用户把 PLAN.md 全文贴过来，你**不要再读源文件、不要查 ggml 源码、不要重新 git log**。直接跳到"五、要干的事"Step 1。
2. **写代码时**：PLAN.md 里写了每个算子的公式和签名，按规格写就行，不要中途去查 llama.cpp 源码。**如果你觉得 PLAN.md 里的公式可能不对，先在 PLAN.md 里加一节验证方法，不要跳过。**
3. **push 前**：跑 `git diff HEAD~1 --stat` 确认只改了预期文件；跑 `git log --oneline -1` 确认没有 token 泄露。
4. **遇到 PLAN.md 里没有的问题**：先更新 PLAN.md，再写代码，这样下次新对话就不用再踩同一个坑。
5. **积分优先级**：写代码 > push + tag > 等 CI > 查源码/重新理解。积分花在产出上，不要花在重复探索上。
6. **小步提交**：一个算子一个 commit，不要一次性写完所有 Step 再 push。
7. **绝对禁止**：改 LlamaJniEngine.kt、改 llama.cpp 下的任何文件、加新的第三方推理框架依赖、硬编码 12/28/1536 这些数字。

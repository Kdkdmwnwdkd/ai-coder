# Xuedi Coder AI APP — 完整状态与接手计划（PLAN.md）

> 本文档是给**下一个执行 AI**的完整指令 + 状态报告。新对话直接把全文粘贴过去即可。
> 仓库地址: https://github.com/Kdkdmwnwdkd/ai-coder
> 主分支: main
> **当前最新版本**: v1.3.25-fix5 (versionCode 37, commit 26ae5b7) ✅ CI 编译成功 ✅ APK 已出
> 目标平台: Android arm64-v8a (minSdk 26, targetSdk 34)
> 目标模型: Qwen2.5-1.5B-Instruct Q4_K_M (940MB GGUF)
> 目标设备: 魅族 20 / 骁龙 8 Gen 2 / 12GB RAM

---

## ⚠️ 紧急：最新 Bug 真相（v1.3.25-fix5 才修复）

**之前 5 个版本 fix1~fix4 全没真正解决问题，因为 GGUF v3 loader 从文件第 16 字节开始就没按规范读！** 4 处根本性格式理解错误叠加：

### 根因总表（按文件位置从头开始数）

| # | GGUF 字段 | 规范 | 之前的错误写法 | 后果 |
|---|----------|------|-------------|------|
| **fix5b** | `header.tensor_count` (offset 8, 8B) | 固定 `uint64` | `vu64()` (ULEB128 变长) | 小值(如 367)碰巧值对，但读 1~2 字节就停，文件位置**少走了 6~7 字节** |
| **fix5b** | `header.metadata_kv_count` (offset 16, 8B) | 固定 `uint64` | `vu64()` (ULEB128 变长) | 又少走 6~7 字节 |
| **fix5c** | KV `value_type` enum | ARRAY=9 / INT8=1 / STRING=8 / FLOAT32=6 | `if (value_type == 1) { /* ARRAY */ }` | 把 INT8 当成 ARRAY 读；真正的 string/array 走错分支，**多/少读 N 字节** |
| **fix5c** | KV 标量值 layout | value_type 直接决定字节数，**没有额外 scalar_type 字段** | `kv.scalar_type = r.r<uint32_t>()` 多读 4B | 每个标量 KV 多跳过 4 字节 |
| **fix5d** | `gguf_type_size(FLOAT32)` | 4 | 8 (错) | 含 float 的 KV (如 rope_freq_base) 多读 4 字节 |
| **fix5d** | `gguf_type_size(BOOL)` | 1 | 8 (错) | 含 bool 的 KV 多读 7 字节 |
| **fix5a** | `LOG(...)` 宏 | 需要 `#include <android/log.h>` | 之前缺 include + define | 导致 fix4 编译失败，正确的 weights_start 逻辑**根本没编进 APK** |
| ~~fix4~~ | tensor offset 相对 weights_start | `abs_off = weights_start + off` ✓ | 之前循环内用 `r.off`（每轮在变）当 base | fix4 逻辑本身对，但因为 fix5a 编译错误**从没运行过** |
| fix2 | tensor info 的 `ne[d]` / `offset` | 固定 `uint64` | 之前 `vu64()` | 之前修过了，已 OK |
| fix3 | `ggml_type_size(Q4_K_M)` 张量字节 | 144 (block 144B) | 之前 256 | 之前修过了，已 OK |

### GGUF v3 官方规范（必须完全照搬，一字之差都要死）

来自 ggml-org/ggml 仓库 `docs/gguf.md` (commit c044a8e)：

```c
// ===== HEADER (24 bytes) =====
struct gguf_header_t {
  uint32_t magic;              // 4B  = 0x46554747 ("GGUF" LE)
  uint32_t version;            // 4B  = 3
  uint64_t tensor_count;       // 8B  固定 uint64，不是 ULEB128！
  uint64_t metadata_kv_count;  // 8B  固定 uint64，不是 ULEB128！
  gguf_metadata_kv_t metadata_kv[metadata_kv_count];
};

// ===== KV VALUE =====
enum gguf_metadata_value_type : uint32_t {
  UINT8=0, INT8=1, UINT16=2, INT16=3, UINT32=4, INT32=5,
  FLOAT32=6,  // 4 bytes, 不是 8！
  BOOL=7,     // 1 byte, 不是 8！
  STRING=8,   // gguf_string_t = ULEB128 len + bytes (无 NUL)
  ARRAY=9,    // array_type(uint32) + arr_count(uint64) + elements
  UINT64=10, INT64=11, FLOAT64=12  // 8 bytes each
};

// ===== TENSOR INFO =====
struct gguf_tensor_info_t {
  gguf_string_t name;          // ULEB128 len + bytes
  uint32_t n_dimensions;       // 4B fixed
  uint64_t dimensions[n_dim];  // 8B each, fixed
  uint32_t type;               // ggml_type enum (Q4_K_M=13, F16=1, F32=0, ...)
  uint64_t offset;             // 8B fixed, 相对于 tensor_data section 起点！不是文件起点！
};

// ===== DATA SECTION =====
// tensor info 读完后，align 到 general.alignment (默认 32) → weights_start
// 每个 tensor data 地址 = p + weights_start + tensor.offset
```

**当前 ggml_loader.cpp 的实现状态**：commit 26ae5b7 里已按上表**全部改对**。从 fix1 到 fix5 一共 10 个坑的清单在上面表格里，不要遗漏任何一个复查。

---

## 一、当前状态快照（v1.3.25-fix5, commit 26ae5b7）

### 1.1 源码结构

```
app/src/main/cpp/
├── ggml_loader.cpp  ← ⭐ 本轮主要工作文件，修了 10 个格式 bug
├── qwen_forward.cpp ← 自写 forward pass（RMSNorm/matmul/RoPE/Attn/SwiGLU/dequant/sampler）
├── qwen_forward.h   ← 对外接口
├── qwen_infer.cpp   ← generate 循环、Session、BPE encode/decode 简化实现
├── qwen_infer.h     ← 数据结构
├── qwen_jni.cpp     ← JNI 桥接：nativeLoadModel / nativeGenerate / nativeRelease / nativeChatCancel
├── llama_jni.cpp    ← 原有 Llama 引擎（先保留做对照组，等 Qwen 稳定后删）
├── llama_jni_stub.cpp
└── CMakeLists.txt   ← 两个 target: qwen-jni（不依赖 llama）+ xuedi-llama（依赖 llama.cpp）

app/src/main/java/.../
├── engine/QwenInferEngine.kt   ← Kotlin 侧 Qwen 引擎包装，开关控制
├── engine/LlamaJniEngine.kt    ← Kotlin 侧 Llama 引擎包装（对照组）
└── App.kt / SettingsScreen.kt  ← 双引擎切换开关 + 诊断页（抓 logcat / 分享诊断包）
```

### 1.2 已验证 / 未验证

| 项 | 状态 | 说明 |
|---|------|------|
| CI 编译 Debug APK | ✅ 通过 | run #154 success |
| APK 大小 | ✅ 正常 | 22.3MB |
| 旧 Llama 引擎 (v1.3.16 路径) 加载模型 | ✅ OK | 用户截图显示 ctx=0x4bffff85737d2ef0 已加载 |
| 旧 Llama 引擎 推理输出 | ❌ 乱码 | 用户截图"你好"回复全是垃圾字符（elsddyuncios$熟 intersectionsce…） |
| Qwen 自写引擎 load_model | ❓ 待验证 | **之前 fix4 的 APK 根本没编译成功**，所以 fix5 是第一次包含正确 GGUF 解析的 APK |
| Qwen 自写引擎 forward 输出 | ❓ 完全未验证 | fix5 之前模型都没加载成功过 |
| BPE tokenize/detokenize 正确性 | ❓ 未验证 | ggml_loader.cpp 里有极简实现，极可能有 bug |
| Q4_K_M dequantization | ❓ 未验证 | 照搬 llama.cpp b5180 公式，但未跑过真实数据 |

### 1.3 用户上次反馈（v1.3.25-beta code 36 / fix3 版 APK）

**两份诊断包要点：**

**第一份（Qwen 引擎开）：**
```
qwen-jni: nativeLoadModel failed: tensor offset out of range
QwenInferEngine: loadModel ❌：nativeLoadModel 返回 false
```
→ 因为 code 36 是 fix3，没包含 fix4 和 fix5（修 GGUF 格式 10 个坑 + LOG 编译错误）。模型加载失败是预期内的。

**第二份（Llama 引擎开）：**
```
LlamaJniEngine: ctx=0x4bffff85737d2ef0 (加载成功)
推理结果: elsddyuncios熟 intersectionsce$errors无辜援
         blanc羕y6hou pregn Każdyedom anchor ...
```
→ Llama 引擎 b5180 能加载 1.5B 模型，但输出乱码。原因之前已经定位过：v1.3.16 只判断 EOS（`<|endoftext|>`），ChatML 格式的 assistant 结束实际输出 `<|im_end|>` (id 151645)，两 token 不同 → 永远不停止 → 输出满 512 tokens 垃圾。后来 v1.3.22 加过 im_end 判断，但 b5180 的 `llama_tokenize` 在魅族 20 上不可靠（v1.3.22 直接崩溃）。

**所以自写 Qwen 引擎是唯一出路，不要在 Llama 引擎上再花时间。**

### 1.4 APK 下载

用户下载地址（本地 HTTP serve）：
```
http://<workspace-host>:8080/AI%E7%BC%96%E7%A8%8B%E5%8A%A9%E6%89%8B-v1.3.25-fix5-code37-arm64-v8a.apk
```
APK 文件本地路径: `/workspace/_serve_apk/AI编程助手-v1.3.25-fix5-code37-arm64-v8a.apk`

CI 构建地址: https://github.com/Kdkdmwnwdkd/ai-coder/actions/runs/33660906694

### 1.5 logcat 标签（用户诊断包里看这几个 tag）

| Tag | 来源文件 | 内容 |
|-----|---------|------|
| `qwen-loader` | ggml_loader.cpp | GGUF 解析：header_end / weights_start / alignment / 每个 tensor 的 off/abs/bytes/ne |
| `qwen-core` | qwen_jni.cpp line 74 | 模型 dump：n_layer/n_embd/vocab_size/关键 tensor 找到没 |
| `qwen-jni` | qwen_jni.cpp | JNI 入口：load 成功/失败、callback method、错误信息 |
| `QwenInferEngine` | Kotlin | 上层 loadModel / chatFlow / cancel 状态 |
| `LlamaJni` / `LlamaJniEngine` | llama_jni.cpp | 旧 Llama 引擎（对照组） |

---

## 二、用户测试步骤（给下一个执行 AI 发用户的）

用户装完 v1.3.25-fix5 APK 后**按顺序做**：

1. **确认版本正确**：「关于」页 → versionName=1.3.25-fix5, code=37
2. **设置页**：打开「使用 Qwen 极简推理器(beta)」开关
3. **模型管理**：点「🔄 重新加载到内存」按钮
4. **观察**：
   - 如果显示 `✅ 已加载到内存 · ctx=...` → 模型加载成功 ✅ → 继续第 5 步
   - 如果显示 `❌ Qwen 推理器加载失败` → **跳到下方 2.1 节** 抓日志
5. **对话页问"你好"**，等待回复
6. **无论结果如何**，去「设置→推理诊断」→ 点「📤 分享诊断包」→ 发给执行 AI

### 2.1 如果模型加载还是失败（tensor offset out of range）

抓诊断包，找 `qwen-loader` 的日志。**正常应该有：**
```
I qwen-loader: GGUF header_end=?????? weights_start=?????? alignment=32
I qwen-loader:   tensor token_embd.weight dtype=13 off=0 abs=?????? bytes=???? ne=[1536,151646,1,1]
...
```
如果没有 `qwen-loader` tag → **CI 构建没包含 LOG 宏定义**，需要看 CMake/NDK 日志是不是编译器搞错。
如果有日志但 header_end 值看起来很小/很大 → 看 n_tensors/n_kv 是不是读对了，继续加日志。
如果 weights_start >= flen (940MB) → alignment/offset 计算仍有问题。

### 2.2 如果模型加载成功，但回复乱码/空/崩溃

问题出在 qwen_forward.cpp 或 BPE tokenizer 或 sampler。**加 debug log 逐步定位：**

1. 先在 `qwen_jni.cpp` 的 `nativeGenerate` 入口把 prompt tokenize 结果的**前 10 个 token id 打印出来**，与 Llama 引擎 tokenize 同样 prompt 的结果对比（如果能拿到）。
2. 在 `qwen_forward.cpp` 里 `forward_step` 入口打印 `pos`、`tokens[0]`、`W_embd` 前几行的 dequant 值，确认 embedding 不是垃圾。
3. 打印 `output_norm` 输出、lm_head matmul 输出前 10 个 logits 值。
4. 看 sampler：argmax 返回的第一个 token id 是不是正常中文 token（一般 2000~15000 左右是常见中文）。如果每次都返回 0 或相同 id → logits 全 0。
5. **重点怀疑对象（按概率排序）**：
   - **BPE tokenize 完全错**（我们用的是最简字节 fallback 版本，没做真正的 merge）
   - Q4_K_M dequant 的 `get_scale_min_k4` 或 nibble 顺序错
   - matmul 行/列主序转置错 (权重的 W_embd 是 [vocab, n_embd] 还是 [n_embd, vocab]?)
   - lm_head 应该 tie `token_embd.weight` 的转置，可能没转
   - RoPE 旋转方向：cos/sin 对调
   - GQA Attention 的 `kv_h = h / rep` 或 repeat 逻辑错
   - RMSNorm epsilon 默认值不对 (qwen2 默认 1e-6, 看 GGUF 里 `qwen2.attention.layer_norm_rms_epsilon`)

### 2.3 如果模型加载成功，回复正常中文 ✅

🎉 **地基第一关跑通！** 下一步按下面「第四节 之后要干的事」顺序推进。

---

## 三、关键代码快速定位

### 3.1 GGUF loader 关键片段

文件: [ggml_loader.cpp](file:///workspace/ai-coder/app/src/main/cpp/ggml_loader.cpp)
- L83-100: gguf_type_size() for KV metadata types (FLOAT32=4, BOOL=1, others strict)
- L102-136: gguf_reader (vu64() only for gguf_string, **never for count/dims/offset!**)
- L226-286: consume_kv_value() (switch value_type 0-12, STRICT GGUF v3)
- L299-311: header parse (magic/version/**n_tensors=r<uint64_t>**/**n_kv=r<uint64_t>**)
- L395-424: tensor info 读 (dims=r<uint64_t>, dtype=r<uint32_t>, off=r<uint64_t>, all FIXED!)
- L425-432: **weights_start = (header_end + alignment - 1)/alignment * alignment** → 这是关键
- L443-463: abs_off = weights_start + ti.off (GGUF v3 spec 严格)

### 3.2 Forward 关键片段

文件: [qwen_forward.cpp](file:///workspace/ai-coder/app/src/main/cpp/qwen_forward.cpp)
- 反量化: dequant_q4_K_M_block() / dequant_tensor() — **必须和 llama.cpp b5180 完全一致**
- 每个算子: rms_norm_fp32 / rope_neox_apply_inplace / swiglu_fp32 / matmul_fp32 / attn_with_kvcache
- KV cache 格式: `kv_cache[layer*2 + kv][pos][kv_head][head_dim]` (fp16)
- Sampler: simple argmax first (跑通再加 temp/top-p)

### 3.3 JNI 桥接关键片段

文件: [qwen_jni.cpp](file:///workspace/ai-coder/app/src/main/cpp/qwen_jni.cpp)
- nativeLoadModel (L99): 调 qwen_load_model，失败打印 qwen-jni: nativeLoadModel failed: XXX
- nativeGenerate (L125): 调 qwen_start_session → qwen_generate 循环 → callback token

---

## 四、之后要干的事（严格按优先级）

### 🔴 P0：用户测 fix5 APK，确认模型能加载

**这是当前最紧急的事！** 直到用户反馈加载成功前，不做其他推进。

**成功后立即 P0a：** 在 `qwen_forward.cpp` 前/中/后加 debug print 输出到 `FWD_LOG` 或 loader LOG，问"你好"看中间变量。目标：**第一句正常中文回复**。

**可能要修的层（按概率从高到低排好队，逐个试，不要同时改两个！）：**

| 顺序 | 嫌疑 | 怎么查 | 修法参考 |
|-----|-------|-------|---------|
| **1st** | BPE tokenize 极简实现不对 | 拿 LlamaJniEngine tokenize("你好") 结果对比（如果能取到）；或在 generate 前 dump 前 20 token id 给 llama.cpp 离线工具验 | 真实现 byte-pair merge loop；或直接嵌入 tokenizer.json 的 merges 数组 |
| **2nd** | Q4_K_M dequant 错 | 随机选 W_embd[0] 前 10 个权重，离线 llama.cpp 工具 dequant 同位置对比 | 逐行对照 llama.cpp b5180 的 get_scale_min_k4 / dequantize_row_q4_K_M 函数 |
| **3rd** | lm_head tie 错/transpose 错 | 正常第一个 token logits 中 "<|im_start|>" 附近应该分数高 | 确认 lm_head 形状: [vocab, n_embd] @ x[n_embd] → [vocab] |
| **4th** | matmul (X@W) 的行列顺序 | X=[1,n_embd] W=[n_embd, n_ff], 结果应为 [1,n_ff]; 不要把权重本身当转置读 | 直接跑一个单元素小矩阵: X=[1,2], W=[[3,4],[5,6]] → 预期 [13, 16] |
| **5th** | RoPE 方向 | 不用 RoPE (直接 apply 0) 先看能不能出"差不多的词"; 如果停用 RoPE 能出中文 → 就是 RoPE 问题 | 翻 llama.cpp RoPE 公式或 qwen.cpp 开源实现 |
| **6th** | GQA repeat / 缓存位置 | 单步打印 Q/K dot product | 对照规范: kv_h = h / (n_head/n_head_kv) |
| **7th** | RMSNorm epsilon | 打印 norm 前后 mean/std | 用 GGUF 里 `qwen2.attention.layer_norm_rms_epsilon` 值, 默认 1e-6 |
| **8th** | EOS/im_end stop | 看生成的 token id 流，151645(im_end) 或 151643(eos) 出现就停 | 硬编码 151645/151643 判断（不要依赖 tokenize）|

### 🔴 P1：如果 Qwen 推理成功输出中文

- 把 Llama 引擎从 APK 删掉（减小体积、消除闪退路径）
  - 文件删列表: llama_jni.cpp, llama_jni_stub.cpp, CMakeLists.txt 去掉 xuedi-llama target
  - Kotlin 删: LlamaJniEngine.kt, App.kt 去掉双引擎分发
  - UI 删: SettingsPage 引擎切换开关
  - 保留 libqwen-jni.so 单一 target

### 🟡 P2：BPE tokenizer 升级到真 merge

当前是字节 fallback（每个单字节一个 token → 不能合并长词 → prompt 太长 → 推理慢）。需要真实现：
```
1. 从 GGUF tokenizer.ggml.merges (KV ARRAY of STRING) 读 merge 列表
2. 把 "a b" 形式的 merge 拆成 pair(a,b) → 构建 map<pair<string,string>, int> 合并优先级
3. tokenize 算法: 先 bytefallback → 循环找最高优先级的 pair → merge → 直到无可 merge
```

### 🟡 P3：联网搜索插件（Kotlin）

DuckDuckGo lite 版抓 HTML 解析。不碰 C++。

### 🟡 P4：GitHub 插件（Kotlin）

匿名读仓库 / 列 commit / 读 raw 文件。用 `https://api.github.com`，token 让用户填 Settings。

### 🟡 P5：代码执行插件（Termux）

检测 Termux → `am start` 或 `termux-exec` 跑 Python。stdout 回聊天框。

### 🟡 P6：图片生成

Termux Python + diffusers 跑 SD 1.5。先能出图不管质量。

### 🟢 P7：NEON SIMD 加速

Q4_K_M dequant 可以 NEON 一次 8 通道；matmul 用 `smull` / `smlal` 4 位乘加；预计 2~3x 提速。

---

## 五、CI / 发布流程（标准操作，不要自己发明）

```bash
# 每次修后步骤
1. git status                    # 确认只改了预期文件
2. git diff --stat               # 同上
3. 修改 app/build.gradle.kts
   - versionCode += 1            # 37→38→39...
   - versionName = "1.3.25-fixN" # fix5→fix6→fix7
4. git add -A
5. git commit -m "v1.3.25-fixN: 一句话说明修啥
                   
详细根因说明...（多行 message 更清楚）"
6. git push origin main
7. 等 5 分钟 → GitHub Actions → 看最新 run 是否 success
   失败 → 看 build log → 修编译错误
8. 成功 → 去 run artifacts 页面下 AI编程助手-debug-*.apk
   或直接用 API:
   GET /repos/Kdkdmwnwdkd/ai-coder/actions/runs/{id}/artifacts
   artifact 名一般是 "AI编程助手-debug-apk"（zip，里面是 apk）
9. 把 apk 拷到 /workspace/_serve_apk/ 开 python -m http.server
   把下载链接发用户
```

**token 存在 git remote URL 里（`https://USER:ghp_XXX@github.com/...`），不要在任何对话/打印里暴露完整值。**

---

## 六、给执行 AI 的指令清单

### 新对话启动 checklist（必按顺序）

1. ✅ 读完整 PLAN.md
2. ✅ 确认 HEAD 是否落后于 main（`git fetch; git status`）
3. ✅ 看用户最新反馈（诊断包 / 聊天截图 / 文字描述）
4. ✅ 判断当前在哪一级：P0 加载 / P0a 正常输出 / P1 删 Llama / P2+ 插件
5. ✅ 不要跳级！加载没过不要碰 forward；forward 没过不要碰插件
6. ✅ 每修一轮：更新 PLAN.md → commit → push → 等 APK → 发用户

### 严格禁止的事

- ❌ **不要改 LlamaJniEngine 或 llama.cpp 代码**：自写引擎是唯一出路
- ❌ **不要改 App.kt / SettingsScreen 引擎切换逻辑**：除非 Qwen 跑通进入 P1
- ❌ **不要引入任何第三方推理/ML 框架**（ggml / onnxruntime / mnn 都不行）
- ❌ **不要自己发明 GGUF 格式或 Q4_K_M 格式**：全部照搬 llama.cpp b5180 或 ggml-org 官方文档
- ❌ **不要同时改多个潜在问题点**：控制变量！一次只改一个嫌疑算子，验证后再改下一个

---

**PLAN.md 最后更新：v1.3.25-fix5 (2026-09-02)。任何重大变更都要先更新本文件再写代码。**

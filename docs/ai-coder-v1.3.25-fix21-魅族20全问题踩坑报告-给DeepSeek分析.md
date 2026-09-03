# AI编程助手 v1.3.25-fix21 魅族20 全链路踩坑报告
## （给 DeepSeek 做深度根因分析用）

生成时间：2026-09-03
设备：meizu MEIZU 20 / arm64-v8a / 骁龙 8 Gen 2 / 11GB RAM（可 用≈3.5-4GB）
引擎：LlamaJniEngine (llama.cpp b5180 稳定版) + QwenInferEngine (自写 ggml 推理器 beta)
模型：Qwen2.5-1.5B-Instruct-Q4_K_M.gguf (940MB)
GitHub：Kdkdmwnwdkd/ai-coder （提交记录完整，tag 从 fix5 到 fix21 每版都有）

---

## 一、当前残留的三大问题（fix21 状态）

| # | 问题 | 现象 | fix21 状态 |
|---|------|------|------------|
| 1 | **Llama 引擎一发消息必 SIGABRT** | 发"你好" → 0.1 秒内闪退，日志最后一条永远是 `✂️ 手动插 BOS`，之后直接 `CRASH CAUGHT: Native signal SIGABRT` | ❌ 未解 |
| 2 | **Qwen 引擎 prefill 3.9~4 秒/token（极慢）** | 发"你好"→ 卡在"正在准备推理"，983 prompt tokens × ~3.9s = 64 分钟才能进入生成阶段，必超时 | ❌ 未解（已不闪退，但仍然太慢） |
| 3 | **第二次消息不显示** | fix18 之前：第一条发完，第二条发送按钮点了但消息不显示。fix18 后通过 `g_gen_running` CAS + `g_cancel` flag 修复 | ✅ 已解 |

## 附带功能修复：
| # | 问题 | 根因 | 修法 |
|---|------|------|------|
| 4 | 诊断包复制按钮看不到 | 三个按钮 Row 用 `spacedBy + Spacer.weight(1)`，窄屏最右"📋复制包"被挤出屏幕 | 三按钮各 `weight(1f)` + 文字缩短到 4 字 |
| 5 | fix19 Qwen 跑 9 分钟 OOM 闪退 | 把 338 个张量全反量化成 F32 缓存 ≈ 2.8GB，吃满 3.5GB 可用内存 | 撤掉全局缓存 |
| 6 | fix20 Qwen 0.01 秒闪退 | 上面缓存删掉后改回单 scratch buffer，但忘了删 5 处 `free(Wq)` → `free(非堆指针)` → abort | 全部 5 处删 free |

---

## 二、各问题完整时间线 + 每次修复 + 反向证据

### 问题 1：Llama 一发消息必 SIGABRT（未解！）

#### 现象（所有版本一致，fix5 到 fix21 全中）
```
16:21:31 I LlamaJni: nativeChat: 🧹 KV cache cleared
16:21:31 I LlamaJni: nativeChat: ⭐ ENTER state=0xb4... ctx=0xb4... n_ctx=4096 n_batch=64 cancel=0
16:21:31 I LlamaJni: nativeChat: 📝 prompt 前 160 bytes: <|im_start|>system
16:21:31 I LlamaJni: nativeChat: tokenize DONE n_prompt=246 / n_ctx=4096
16:21:31 I LlamaJni: nativeChat: ✂️ 手动插 BOS: bos_id=151643, 新 n_prompt=247, token[0]=151643
16:21:31 E LlamaJni: CRASH CAUGHT: Native signal SIGABRT: code=-1 addr=0x...
```
**中间 0 条日志** → 从 `✂️ 手动插 BOS` 到 `CRASH` 之间我们加过 S1-S6 的定位代码（每步 LOGI+fflush），**一次都没打出来过**。

#### 踩过的修复（全部无效！互相矛盾的根因假设）

| 版本 | 假设根因 | 修改 | 结果 | **反证** |
|------|---------|------|------|---------|
| fix10 | n_batch=8 太大 → batch decode 崩 | `cparams.n_batch=1` + `cparams.n_ubatch=1` | ❌ 仍然 SIGABRT | nativeInit 日志：`cparams.n_batch=1 n_ubatch=1` 已生效，但 `llama_n_batch(ctx)` 返回 64！！被 llama 内部覆盖了 |
| fix14 | prefill 用了 `llama_batch_get_one(246)` 一次喂太多 → 改成每次 1 token | 手写 while 循环，`llama_batch_init(SAFE_BATCH=1,0,1)` + 每次只写 1 token | ❌ 仍然 SIGABRT | 日志 "⏳ prefill #0" 从没出现 → 崩在 llama_decode 调用前的代码 |
| fix17 | state->n_batch 被 llama 覆盖回 64 → batch_init 实际是 64 | `constexpr SAFE_BATCH=1` 完全不用 `state->n_batch`，硬编码 batch_init(1) | ❌ 仍然 SIGABRT | 说明不是 batch 数组大小问题 |
| fix19 | 4 线程 llama_decode race assertion | `cparams.n_threads=1` + `n_threads_batch=1`（彻底单线程） | ❌ 仍然 SIGABRT | 说明不是线程 race |
| fix20 | `llama_sampler_chain_init / llama_sampler_accept(247 tokens)` 内部 assertion | 全系列撤掉！改 `llama_get_logits()` + 手写 O(n_vocab) argmax for 循环** | ❌ 仍然 SIGABRT（用户刚测） | **致命反证：现在 crash_handler 前甚至连 sampler 代码都不执行了，还是崩 → 说明崩溃点在 sampler chain 之前！** |

#### 当前已知的真·崩溃边界（fix21 最新状态）
```cpp
// llama_jni.cpp nativeChat:
LINE 630: crash_guard_push();      // 装 SEGV/ABRT/BUS 信号处理
...
LINE 665: 手动插 BOS 完成          // ← 最后一条能看到的日志：✅ 一定到过这里
...
LINE 692: 进入 prefill while 循环  // ← 日志 "[fix20] greedy argmax" 从没看到过？
                                        实际用户说 0.01 秒闪退 → 很可能还没打出来
...
```
**DeepSeek 需要回答的问题：**
1. b5180 的 llama.cpp 在 `llama_load_model_from_file` 成功，到第一次 llama_decode 之间，有哪些点会 SIGABRT？（特别是骁龙 8 Gen 2 / arm64-v8a）
2. 我们有 `crash_guard_push()`（sigaction 装了 handler），但 S1/S2 日志（如果有）还是没打出来 → 会不会是 llama_jni.cpp 里 `crash_guard_push` 之前就有隐式 abort？比如 Java 层的 `System.loadLibrary` 会不会在第二次加载时因为 symbol 冲突 abort？（但日志里 libLoaded=true）
3. `llama_batch_init(1,0,1)` 本身在 b5180 + Qwen2 GGUF（vocab=151936, n_embd=1536, type=MODEL_TYPE_7B？不对，1.5B 有没有内部 assertion 对 tensor shape 的检查？）
4. 会不会是 llama 模型架构类型（Qwen2）和 llama.cpp b5180 的 vocab 处理（tokenizer type BPE/WPM？）有冲突，第一次 llama_decode 前做内部 tokenizer 检查就 assert？

---

### 问题 2：Qwen 自写引擎 3.9-4 秒/token（未解！）

#### 现象（fix18 之后有 prefill progress 日志确认）
```
prefill progress: 19/983 tokens (1%), elapsed 73685ms, avg 3878.2 ms/tok   ← fix18
prefill progress: 38/983 tokens (3%), elapsed 149915ms, avg 3945.1 ms/tok
prefill progress: 57/983 tokens (5%), elapsed 227520ms, avg 3991.6 ms/tok
```
983 tokens × 3.9s ≈ **64 分钟才 prefill 完**，首 token 超时（300s）永远达不到。

#### 踩过的修复（全部矛盾！）

| 版本 | 假设根因 | 修改 | 结果 | **反证** |
|------|---------|------|------|---------|
| fix18-之前 | naive matmul 慢（j,i 循环 + 无 NEON） | vec_mat 改成 `i外j内` + NEON vfmaq 4-float 并行 + 4线程池并行切行 | ❌ 仍然 3.9 秒/token | 说明瓶颈不在 vec_mat！dequant 本身才是瓶颈 |
| fix19 | `dequant_tensor()` 每 token 每层 7 次 malloc 7MB 权重 → 开销 ~200MB malloc/秒 | `unordered_map<tensor_ptr, F32Cache>` 把 338 张量一次性全反量化成 F32 ≈ 2.8GB RAM | ❌ **新Bug：9 分钟 OOM 闪退** | 魅族 20 可用内存 ≈ 3.5-4GB，2.8GB 缓存吃满后被 Android LMKD 杀进程 |
| fix20 | 上面缓存 OOM → 改 60MB 单 scratch buffer 复用 | `g_qw_fp32_scratch` 单 vector + mutex，每次 dequant 覆盖写 | ❌ **新Bug：0.01 秒闪退** | 忘了删调用处 5 处 `free(Wq)` → `free(非堆指针)=abort` |
| fix21 | 把 free 全删掉 → 恢复到"单次 buffer 复用 + 重新 dequant"状态 | 删 5 处 free | ✅ 不闪退了 | ❌ **又回到 3.9 秒/token 的原始状态** |

#### 当前真·瓶颈（fix21 现状）
```cpp
// qwen_forward.cpp:
for 每 token (983):
    for 每 layer (28):
        dequant w_attn_q (1536×1536 type=Q4_K=12)  // ~ 反量化 ~1.2MB 量化数据
        dequant w_attn_k (1536×256 type=Q4_K)
        dequant w_attn_v (1536×256 type=Q4_K)
        dequant w_attn_o (1536×1536 type=Q4_K)
        dequant w_ffn_gate (1536×8960 type=Q4_K) // ~7.7MB 量化！这是大头
        dequant w_ffn_up   (1536×8960 type=Q4_K) // ~7.7MB
        dequant w_ffn_down (8960×1536 type=Q5_K=14) // ~9.4MB Q5_K 更慢！
        → 7× dequant ≈ 28MB 反量化 per layer × 28 layer ≈ 784 MB dequant per token
        → 784MB × 3.9s = 201 MB/s 反量化吞吐量（符合 naive Q4_K_M 的速度）
```

**DeepSeek 需要回答的问题：**
1. 给 `dequant_q4km_tensor()` 写 NEON 版本！当前是纯标量 C。llama.cpp 里的 `dequantize_row_q4_K_neon` 是怎么写的？能不能直接移植？
2. Q5_K 同理也需要 NEON。ffn_down 是 Q5_K，占 dequant 时间的 ~40%。
3. 真正合理的缓存方案：**每层只存 F32 权重**，但总数只反量化 338 个 tensor 的 1/10？（还是内存峰值 ~2.8GB 太大 → 那能不能 pre-dequant 到磁盘？或者按 layer 缓存：处理 layer N 时把 N 的 F32 放内存，用完不丢，下一个 token 直接用。总内存 940MB Q4_K_M → 2.6GB F32，但 11GB 手机可用只有 3.5GB，剩下的 900MB 给 KV cache + 系统够吗？）
4. 另一个方案：**prefill 跳过对 prompt token 的 full forward**，有没有办法对 983 个 prompt tokens 共享同一层的权重（只反量化一次，做 983 次 matmul）？→ 现在是对每个位置 token 都重做 dequant + forward，O(28L × 983pos) 的 dequant。实际正确做法是 dequant 一次权重然后 matmul 整个 prompt 矩阵 → dequant 只有 28L 次不是 28×983 次！

**这才是真正的根因！现在的推理器架构有根本性错误：应该做 `prefill(n_prompt_tokens)` 批处理，权重只反量化一次，然后和 prompt 矩阵乘（或循环 prompt）。现在是把 prefill 当成 generate 一样逐 token forward，导致每层权重重复反量化 983 次！！！**

---

### 问题 3：第二次消息不显示（已解）

#### 根因（fix18 前）
QwenInferEngine 的 cancel() 是空函数：
```kotlin
// 旧版
override fun cancel() {
    Log.i(TAG, "cancel() 被调用（初版 C++ 暂不支持中途取消...")
    // 什么都没做！native 线程继续跑 64 分钟 prefill！
}
```
→ 第一条"你好"超时 5 分钟后 UI `onTimeout` 调了 cancel 但 native 还在跑。第二条 send 时 `nativeGenerate()` 被再调用一次：
```cpp
// 旧版 qwen_jni.cpp 没有互斥
Java_com_xuedi_coder_model_QwenInferEngine_nativeGenerate(...) {
    // 两次并发进入！QwenModel / QwenSession 内部没有加锁 → 堆内存踩踏 / 死锁
    qwen_run_session(...);
}
```
→ 第二条 UI 回调 Flow 收不到 token，UI 上第二条消息存在但一直"正在准备推理"。

#### fix18 修复
```cpp
// qwen_jni.cpp:
static std::atomic<bool> g_gen_running{false};   // CAS 互斥
static std::atomic<bool> g_cancel{false};         // 取消 flag

JNIEXPORT void JNICALL nativeGenerate(...) {
    bool expected = false;
    if (!g_gen_running.compare_exchange_strong(expected, true)) {
        cb_error(env, callback, "上一次推理仍在运行，请稍后或重启App");
        return; // 防止重入
    }
    g_cancel.store(false);
    ...
    while (...) {
        if (g_cancel.load()) break;   // 每 token 检查
        ...
    }
    g_gen_running.store(false);
}
```
```kotlin
// QwenInferEngine.kt 新增：
private external fun nativeCancel()
override fun cancel() {
    Log.i(TAG, "cancel() → nativeCancel (g_cancel=true)")
    runCatching { nativeCancel() }
}
```

---

## 三、关键代码段（DeepSeek 直接看的）

### 1. Llama SIGABRT 区域（llama_jni.cpp 670-710 行）
```cpp
// 670: tokenize 完成
LOGI("nativeChat: 📌 vocab eos=%d bos=%d, tokenize DONE n_prompt=%d / n_ctx=%d (耗时 %d ms)",
     eos, bos, (int)n_prompt, (int)state->n_ctx, (int)(now_ms()-t0tok));

// 672: 手动插 BOS ← 最后一条成功日志永远是下面这条
{
    std::vector<llama_token> new_tokens;
    new_tokens.resize(tokens.size() + 1);
    new_tokens[0] = bos;
    memcpy(new_tokens.data() + 1, tokens.data(), tokens.size() * sizeof(llama_token));
    tokens = std::move(new_tokens);
    n_prompt = (int32_t)tokens.size();
    LOGI("nativeChat: ✂️ 手动插 BOS: bos_id=%d, 新 n_prompt=%d, token[0]=%d",
         (int)bos, (int)n_prompt, (int)tokens[0]);
}

// 681-690: fix20 删掉了 sampler_chain 全系列
// 直接进 prefill while (fix20 应该先打 "[fix20] greedy argmax" 日志，但从没看到！！)

// ... 中间 prefill 循环 ...
```
**DeepSeek 请重点看：为什么 `✂️ BOS` 之后立刻 SIGABRT？prefill while 之前的代码里有什么会 abort？**
- `tokens = std::move(new_tokens)` 这行 vector resize+memcpy 没问题吧？
- crash_guard_push 是不是已经覆盖 SIGABRT？会不会在 `sigaction` 里有什么问题？
- 会不会是 JNI 里 `env->CallVoidMethod` 调用 Java 回调时，DetachCurrentThread/Attach 的问题？但最后一条成功日志是 `✂️ BOS`，那之前回调都没调过（第一次回调是 prefill onPrefill 在 llama_decode 后面）。

### 2. Qwen 3.9s/tok 的 `dequant_q4km_tensor` 原始实现（qwen_forward.cpp 约 70-120 行）
→ 请 DeepSeek 改成 NEON 版本

### 3. Qwen forward 架构错误（qwen_forward.cpp `transformer_layer` 调用链）
```
fwd_forward_step(sess, model, token, pos)   // 单个 token forward
  → sess.x[1536] 里是 embedding 后的
  → for 28 layer:
      transformer_layer(x, layer_idx, pos, m, k_cache_layer, v_cache_layer)
        → dequant 7 tensors  // ← 每个 token 都重 dequant！！！
        → vec_mat q/k/v/gate/up/down
```
→ **DeepSeek 请建议正确的 prefill 批处理架构**：应在 load model 时先把 338 tensor 反量化成 F32（如果内存够），或 prefill 时对 `prompt_tokens[983]` 一次喂多个，权重反量化一次后用 GEMM（不是逐 token）。

---

## 四、版本对别表（tag → 下载 APK → 现象），供 DeepSeek bisect
| tag | code | Llama闪退 | Qwen速度 | Qwen闪退 | 二次消息 | 复制按钮可见 |
|-----|------|----------|---------|----------|---------|------------|
| fix16 | 48 | ✅闪退 | ~13.8s/tok? | ❌不闪退 | ❌不行 | ❌超出屏幕 |
| fix17 | 49 | ✅闪退 | ~3.9s/tok | ❌不闪退 | ✅行了 | ❌超出屏幕 |
| fix18 | 50 | ✅闪退 | ~3.9s/tok | ❌不闪退 | ✅行了 | ❌超出屏幕 |
| fix19 | 51 | ✅闪退 | **未测（先OOM）** | ❌ **9分钟OOM闪退** | ✅行了 | ❌超出屏幕 |
| fix20 | 52 | ✅闪退 | **未测（先free崩溃）** | ❌ **0.01秒free(SIGABRT)** | ✅行了 | ✅三按钮平分（可见） |
| **fix21** | **53** | **✅闪退（当前）** | **~3.9s/tok（当前）** | **✅不闪退了** | ✅行了 | ✅三按钮平分（可见） |

---

## 五、当前代码位置（绝对路径）

| 文件 | 行数 | 内容 |
|------|------|------|
| `app/src/main/cpp/llama_jni.cpp` | 630-880 | Llama nativeChat：BOS → prefill → generate 全链路 **（SIGABRT 根因所在地）** |
| `app/src/main/cpp/llama_jni.cpp` | 350-450 | Llama nativeInit：cparams、ctx/vocab 加载 |
| `app/src/main/cpp/qwen_forward.cpp` | 237-281 | dequant_tensor（当前 60MB scratch）+ fix21 注释 |
| `app/src/main/cpp/qwen_forward.cpp` | ~70-170 | dequant_q4km_tensor / dequant_q5k_tensor（纯标量！需NEON） |
| `app/src/main/cpp/qwen_forward.cpp` | 610-703 | transformer_layer：dequant 7 tensors + vec_mat + swiglu **（架构错误所在地）** |
| `app/src/main/cpp/qwen_jni.cpp` | 顶部+nativeGenerate | g_gen_running/g_cancel + nativeCancel（二次消息修复） |
| `app/src/main/java/com/xuedi/coder/model/QwenInferEngine.kt` | cancel() + nativeCancel() | Kotlin 侧 cancel 调用链 |
| `app/src/main/java/com/xuedi/coder/ui/screen/SettingsPage.kt` | 1316-1358 | 诊断按钮 Row（fix20 三平分 weight1/1/1） |
| `app/build.gradle.kts` | 27-28 | 当前 versionCode/versionName |

---

## 六、给 DeepSeek 的具体请求（按优先级）

**P0：Llama SIGABRT 到底在哪？**
- 基于 b5180 代码 + 我们的调用顺序 + 日志（BOS 之后立即崩），列出所有可能的 assertion 点，按概率排序
- 给一版**最小安全调用链**：从 `llama_load_model_from_file` 成功后，到**绝对不会 SIGABRT**的 llama_decode 之前的最小代码（例如：要不要手动 kv cache init？要不要 `llama_sampler` 必须有？`llama_get_logits` 只能在一次 decode 之后才能调？）

**P1：Qwen 架构级修复 — prefill 批处理**
- 把当前逐 token dequant+forward，改成：权重只 dequant 一次 + 对 prompt_tokens[983] 做矩阵化 forward
- 或者至少：dequant_q4km_tensor + dequant_q5k_tensor 改 NEON 版本（移植 llama.cpp 对应实现）

**P2：内存峰值估计算**
- 1.5B Q4_K_M → F32 全部权重 = 多少 MB？加上 KV cache(4096ctx × 28layer × 2head_kv × 128 head_dim × 2B KV per float16) = 总内存峰值？
- 魅族 20 11GB RAM 手机、系统占 7GB、剩 3-4GB 可用、能不能放得下全 F32 权重 + KV + 运行时？

---

End of report.

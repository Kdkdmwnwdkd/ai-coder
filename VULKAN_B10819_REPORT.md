# Vulkan 真启用 + llama.cpp b10819 升级报告（code278/279）

> 生成时间：2026-09-06 ｜ 对应 CI run：34024538694（✅ 绿）｜ 提交：`bbc445d`
> 验证结论：**APK 内 libxuedi-llama.so 已确认 DT_NEEDED=libvulkan.so + 4 个 Vulkan 符号引用，Vulkan 这次是真的编进去了。**

---

## 一、本次做了什么

### 1. llama.cpp b5180 → b10819（官方原版，零补丁）
- 下载源：`app/build.gradle.kts` 的 `TAG = "b10819"`，从 codeload.github.com/ggml-org/llama.cpp 拉官方 tarball，带 `.xuedi_src_tag` 戳记防旧缓存。
- **上游源码一行没改**，以后升级只改 TAG 一个字符串。
- API 适配（`llama_jni.cpp`）：`llama_kv_self_clear` → `llama_memory_clear(llama_get_memory(ctx), true)`；`flash_attn=false` → `flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED`。

### 2. 批量 prefill 重开（带崩溃自锁）
- `llama_jni.cpp` 5.0 节：prompt ≤1024 token 时一次性 `llama_decode`，替代逐 token。
- ctx 参数：`n_batch=1024 / n_ubatch=512`。
- **崩溃自锁**：尝试前先写 `<model>.batch_bad` 锁文件 → 成功删锁；ret!=0 保留锁+清KV+回退逐 token；真 SIGSEGV 则锁残留，下次启动永久逐 token（最多崩一次）。
- 诊断：prefMode = BATCH_OK / BATCH_FB / STEPx1，会进诊断包。

### 3. Vulkan 真启用（本轮核心）
- `CMakeLists.txt` 顶部 `find_package(Vulkan COMPONENTS glslc)`，失败自动退 CPU-only（永不阻塞出包）。
- CI 装 `glslc + spirv-headers + libvulkan-dev`（debug/release 双 job）。
- 用户级开关保留：设置页 → 推理偏好 → GPU 加速（关闭=强制 CPU 加载）。

### 4. 删除模拟模式（用户要求）
- `MockLlmEngine.kt` 整文件删除；`forceMockMode` 开关、native 失败的假回复兜底、设置页"模拟模式"分组全清。
- 现在 native 出错 → 直接 `ChatChunk.Error` 带原因，**绝不吐假回复**。

### 5. 删除 1.5B 快模式（用户要求）
- `ModelPrefsStore.useFast1_5BDefault` 及设置页开关删除。
- `ModelManager.autoSelectInitialByPrefs`：固定优先 3B（没 3B 用第一个；用户手动选过的永远优先）。

---

## 二、踩过的 6 个坑（下次升级 llama.cpp 必读！）

| # | 坑 | 根因 | 修法 |
|---|---|---|---|
| 1 | CI 绿但 APK 没 Vulkan | CMakeLists 里 `find_package(Vulkan)` 调用本体漏写，只剩 if 判断 → 永远 CPU-only | 恢复调用；**验证必须拆包看 .so，不能信 CI 绿灯** |
| 2 | `find_package(SPIRV-Headers)` FATAL | b10819 ggml-vulkan 新增硬依赖（b5180 没有），Android 交叉编译找不到宿主编 cmake 包 | 预置 `SPIRV-Headers_DIR=/usr/share/cmake/SPIRV-Headers`（NO_CMAKE_FIND_ROOT_PATH） |
| 3 | `vulkan.hpp: No such file` | b10819 改用 vulkan-hpp C++ 绑定，NDK 只发 C 头 | 从宿主 libvulkan-dev 隔离拷贝 `vulkan/`+`vk_video/` 到构建目录（不能直接 -I/usr/include，glibc 头会污染交叉编译） |
| 4 | include 目录神秘消失 | CMake 把 /usr/include 当隐式目录，自动从 -I 剥掉 | 同 #3，隔离拷贝到 `$CMAKE_BINARY_DIR/host-vulkan-hpp` |
| 5 | 131MB shader 表编译内存爆炸 | `mul_mm.comp.cpp` 等是纯数据表，-O2/-O3 吃 4GB+ 内存，6GB 沙箱直接 OOM | 对 `*.comp.cpp` 强制 `-O0`（纯数据零优化收益）；GitHub runner 16GB 无压力 |
| 6 | `ld.lld: undefined vkGetPhysicalDeviceFeatures2` | NDK libvulkan.so stub 按 API 分级：**API≤27 只导出 Vulkan 1.0 符号**（nm 实测），b10819 直接引用 1.1 符号 | 链接**最高 API 等级 stub**；运行时 Android 函数符号懒绑定，老设备 ggml 探测 apiVersion<1.2 自动禁用后端，不会崩 |

---

## 三、核心代码索引

| 文件 | 关键位置 |
|---|---|
| `app/src/main/cpp/llama_jni.cpp` | 5.0 节批量 prefill + `.batch_bad` 崩溃锁；常量区 `CTX_N_BATCH=1024 / PREFILL_BATCH_MAX=1024` |
| `app/src/main/cpp/CMakeLists.txt` | 顶部 Vulkan 检测段（NDK sysroot 显式定位 + SPIRV-Headers_DIR + 最高 API stub）；`*.comp.cpp` -O0 段 |
| `app/build.gradle.kts` | `ensureLlamaCppSource`：TAG=b10819 + 戳记 |
| `.github/workflows/build.yml` | 双 job apt：`glslc spirv-headers libvulkan-dev` |
| `model/LlamaJniEngine.kt` | chatFlow（插件链→lib检查→ctx检查→CAS锁→callbackFlow）；native 失败→Error |
| `model/ModelManager.kt` | `autoSelectInitialByPrefs`（固定优先 3B） |
| `model/ModelPrefsStore.kt` | 只剩 `useVulkanAccel` 一个开关 |

---

## 四、真机验证清单（装包后看 logcat）

```bash
adb logcat -s LlamaJni
```

| 关键词 | 含义 |
|---|---|
| `Vulkan`（loadModelRobust 阶段）| Vulkan 后端被 ggml 探测到并启用 |
| `PREFILL-BATCH PASS` + prefMode=`BATCH_OK` | 批量 prefill 成功（prefill 应明显变快） |
| `BATCH_FB` | 批量失败已回退逐 token（同时留了崩溃锁，属正常保护） |
| `🔒 PREFILL-BATCH: 检测到批量崩溃锁` | 上次崩过，永久逐 token（安全路径） |

设置页诊断包里也会带 prefMode / 引擎版本（b10819 · code278/279）。

## 五、出问题怎么办

- **Vulkan 在设备上初始化失败**：`loadModelRobust` 检测到 Vulkan 关键词错误会自动把 gpuHint 切 0 降级 CPU；用户也可在 设置→推理偏好 手动关。
- **批量 prefill 崩了一次**：不用管，`.batch_bad` 锁自动保护，下次起永久逐 token。想重试：删掉模型旁的 `<model>.batch_bad` 文件。
- **要彻底关 Vulkan 出 CPU-only 包**：CMakeLists.txt 里把 `find_package(Vulkan ...)` 段删掉/注释即可（GGML_VULKAN 默认 OFF），其余代码零改动。

## 六、后续待办（用户计划，预计 500-600 积分）

1. 代码质检 / 漏洞自查（Kotlin + JNI 内存安全：nativeChat 回调生命周期、ctx 释放路径、wakelock 泄漏）
2. UI 优化
3. AI 回复质量优化（采样参数：目前是纯 argmax，可考虑加温度/top-p）
4. Vulkan 性能调优（真机对比 CPU vs GPU 的 prefill/decode 速度后再定参数）

---

### 验证证据存档

```
$ llvm-readelf -d lib/arm64-v8a/libxuedi-llama.so | grep NEEDED
  liblog.so / libz.so / libomp.so / libm.so / libdl.so / 【libvulkan.so】 / libc.so
$ llvm-nm -D ... | grep -c ' U vk'  →  4   (vkGetPhysicalDeviceFeatures2 等)
CI: run 34024538694, BUILD SUCCESSFUL in 5m10s, commit bbc445d
```

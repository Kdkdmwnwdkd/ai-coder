# =====================================================
# 【v1.3.25-fix22】Llama 引擎精简版 CMakeLists.txt
# —— 按用户「死命令」物理删除所有自写 C++ 推理代码，
#    只保留 1 个官方 llama.cpp JNI 入口：llama_jni.cpp → libxuedi-llama.so
#    qwen-jni 整段已移除（不再维护自写 Qwen 推理器）
# =====================================================

cmake_minimum_required(VERSION 3.22.1)
project(xuedi-llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# 强制所有依赖编译为 STATIC（.a），最终合并进 libxuedi-llama.so
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# ---- 防 stub · 关键检查 ----
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "------------------------------------------------------------\n"
        "FATAL: llama.cpp 源码缺失！\n"
        "  找不到文件：${LLAMA_DIR}/CMakeLists.txt\n"
        "\n"
        "  Gradle preBuild task ensureLlamaCppSource 会自动从\n"
        "  https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/b5180\n"
        "  下载并解压到 cpp/llama.cpp/。\n"
        "\n"
        "  手动解决：把 b5180 tag 压缩包解压，把 llama.cpp-b5180/ 重命名为 llama.cpp/\n"
        "  放在 app/src/main/cpp/ 下（目录结构必须 app/src/main/cpp/llama.cpp/CMakeLists.txt 直接存在）。\n"
        "------------------------------------------------------------")
endif ()

# ---- llama.cpp 编译选项（Android ARM64 交叉编译专属）----
set(LLAMA_STATIC          ON  CACHE BOOL "" FORCE)
set(LLAMA_NATIVE          OFF CACHE BOOL "" FORCE)
set(LLAMA_LTO             OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)

# x86 SIMD 全关（目标是手机 ARM64）
set(LLAMA_AVX             OFF CACHE BOOL "" FORCE)
set(LLAMA_AVX2            OFF CACHE BOOL "" FORCE)
set(LLAMA_AVX512          OFF CACHE BOOL "" FORCE)
set(LLAMA_FMA             OFF CACHE BOOL "" FORCE)
set(LLAMA_F16C            OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_AVX              OFF CACHE BOOL "" FORCE)
set(GGML_AVX2             OFF CACHE BOOL "" FORCE)
set(GGML_FMA              OFF CACHE BOOL "" FORCE)
set(GGML_F16C             OFF CACHE BOOL "" FORCE)
set(GGML_AVX512           OFF CACHE BOOL "" FORCE)

# ARM/Android 优化
set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM          ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_MALI     OFF CACHE BOOL "" FORCE)

# =====================================================
# 【vc65 CPU 回滚 · 2026-09-04】
# 背景：vc64 Vulkan 包发送消息后 3~4s SIGSEGV 闪退。
# 根因：使用 include_directories(BEFORE) 把系统 v275 的 vulkan_core.h
#   放在了 NDK sysroot 之前，导致 VkPhysicalDeviceProperties 等 C 结构体
#   与手机实际 libvulkan.so (VK_HEADER_VERSION=237) 的字段数量/大小不一致，
#   ggml_vk_instance_init 阶段 VkDevice/VkInstance 初始化写爆栈（即便
#   n_gpu_layers=0 也会先做设备探测，必触发栈溢出 → 3~4s 后崩溃）。
# 正确的 Vulkan 修复：只把 vulkan.hpp / vulkan_enums.hpp / vulkan_structs.hpp
#   等 C++ 头替换为 v275（修复 NDK 重定义 bug），C 层 vulkan_core.h 必须
#   保留 NDK sysroot 版本（保证与 runtime 同版本）。留待下一轮 Vulkan 验证。
# 当前决策：优先保证可用性，回滚到 100% 稳定的 CPU-only 构建（与 code62 底包
#   完全等价，minSdk=26，versionCode=65，覆盖升级即可）。
# =====================================================
message(STATUS "[xuedi-llama][vc65] 回滚 CPU-only（与 code62 底包等价）— Vulkan 等下一轮 header 对齐后再启用")
set(GGML_VULKAN   OFF CACHE BOOL "" FORCE)
set(XUEDI_HAS_VULKAN OFF)

set(LLAMA_ARM_NEON        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_NEON     ON  CACHE BOOL "" FORCE)

# 避免 llama.cpp 子工程把 WARNING 当 ERROR
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(LLAMA_WERRORS         OFF CACHE BOOL "" FORCE)

# =====================================================
# 把 llama.cpp 作为子目录拉进来
# 会产出 4 个 static lib target：ggml-base、ggml-cpu、ggml、llama
# =====================================================
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama_build)

# =====================================================
# 唯一 JNI 入口：llama_jni.cpp → libxuedi-llama.so
# 不再编译任何自写的 qwen_forward / qwen_infer / ggml_loader
# =====================================================
add_library(xuedi-llama SHARED
    llama_jni.cpp
)

target_include_directories(xuedi-llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
)

# 把“编译期有无 ggml-vulkan”传给 llama_jni.cpp，避免 CPU-only 编译下把 n_gpu_layers>0 的 hint 浪费。
target_compile_definitions(xuedi-llama PRIVATE
    XUEDI_LLAMA_VULKAN=$<IF:$<BOOL:${XUEDI_HAS_VULKAN}>,1,0>
)

# 严禁写 common — b5180 + LLAMA_BUILD_EXAMPLES=OFF 时该 target 不存在
# Vulkan 回退：GGML_VULKAN=ON 时 llama.cpp 会自动把 ggml-vulkan link 到 ggml（它自己做 backend 注册），
#              我们这里只显式加一份以防静态合并时 target 依赖被剪；
#              Vulkan::Vulkan 由 ggml-vulkan target 自己 PRIVATE link，jni 这边不用再重链。
target_link_libraries(xuedi-llama PRIVATE
    llama
    ggml
    ggml-cpu
    ggml-base
    $<$<BOOL:${XUEDI_HAS_VULKAN}>:ggml-vulkan>
    log
    z
)

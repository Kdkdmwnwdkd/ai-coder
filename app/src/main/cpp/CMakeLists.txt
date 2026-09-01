# =====================================================
# 【M5-2】xuedi-coder JNI 入口 CMakeLists.txt
# —— 对应 #26 失败教训（2026/09/01 Run#26 ld.lld error: unable to find library -lcommon）——
#   1. llama.cpp b4835 在 LLAMA_BUILD_EXAMPLES=OFF 时不编 common target，
#      链接写 -lcommon 直接炸 → target_link_libraries 中移除 common。
#   2. 强制所有依赖编译为静态库（BUILD_SHARED_LIBS=OFF），
#      最终只有一个 libxuedi-llama.so 打进 APK，避免多 .so 拆分后的加载路径/符号问题。
#   3. 其他 ARM64 交叉编策略（x86 SIMD 全关 + GGML_OPENMP + ARM NEON）保留。
# =====================================================

cmake_minimum_required(VERSION 3.22.1)
project(xuedi-llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# —— 关键：让 ggml / llama / ggml-cpu / ggml-base 全部编译为 STATIC（.a），
#        最终合并进 libxuedi-llama.so。否则默认 BUILD_SHARED_LIBS=Not Set，
#        add_subdirectory 里的 add_library() 会按 STATIC 还是 SHARED 取决于
#        llama.cpp 自己的 BUILD_SHARED_LIBS 设置，且不同 target 不一致。
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)

# ---- 防stub · 关键检查 ----
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "------------------------------------------------------------\n"
        "FATAL: llama.cpp 源码缺失！\n"
        "  找不到文件：${LLAMA_DIR}/CMakeLists.txt\n"
        "\n"
        "  正常流程：Gradle preBuild task ensureLlamaCppSource 会自动从\n"
        "  https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/b4835\n"
        "  下载并解压到 cpp/llama.cpp/。\n"
        "\n"
        "  你看到这个错误 99% 是 Actions Runner 网络问题导致 curl 下载失败。\n"
        "  手动解决：把 b4835 tag 压缩包解压，把 llama.cpp-b4835/ 重命名为 llama.cpp/\n"
        "  放在 app/src/main/cpp/ 下（目录结构必须 app/src/main/cpp/llama.cpp/CMakeLists.txt 直接存在）。\n"
        "------------------------------------------------------------")
endif ()

# ---- llama.cpp 编译选项（Android ARM64 交叉编译专属）----
# 必须在 add_subdirectory 之前写 CACHE BOOL，
# 否则子工程的 option() 会覆盖我们的设置。
set(LLAMA_STATIC          ON  CACHE BOOL "" FORCE)
set(LLAMA_NATIVE          OFF CACHE BOOL "" FORCE)
set(LLAMA_LTO             OFF CACHE BOOL "" FORCE)  # NDK LTO 在部分 AGP 版本有问题
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)

# — x86 SIMD: 全部硬关（目标是手机 ARM64）—
set(LLAMA_AVX             OFF CACHE BOOL "" FORCE)
set(LLAMA_AVX2            OFF CACHE BOOL "" FORCE)
set(LLAMA_AVX512          OFF CACHE BOOL "" FORCE)
set(LLAMA_FMA             OFF CACHE BOOL "" FORCE)
set(LLAMA_F16C            OFF CACHE BOOL "" FORCE)

# ggml 层同名控制：一样全关
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_AVX              OFF CACHE BOOL "" FORCE)
set(GGML_AVX2             OFF CACHE BOOL "" FORCE)
set(GGML_FMA              OFF CACHE BOOL "" FORCE)
set(GGML_F16C             OFF CACHE BOOL "" FORCE)
set(GGML_AVX512           OFF CACHE BOOL "" FORCE)

# — ARM/Android 优化 —
set(GGML_OPENMP           ON  CACHE BOOL "" FORCE)   # 多线程（NDK clang 自带 libomp）
set(GGML_CPU_ARM          ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_MALI     OFF CACHE BOOL "" FORCE)  # Mali GPU 驱动碎片化严重，先不搞
set(GGML_VULKAN           OFF CACHE BOOL "" FORCE)  # Vulkan loader 兼容性复杂，先纯CPU

# ARM NEON：minSdk 26 + arm64-v8a ABI，100% 支持
set(LLAMA_ARM_NEON        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_NEON     ON  CACHE BOOL "" FORCE)

# 避免 llama.cpp 子工程把 WARNING 当 ERROR（NDK clang 有些 warning 会炸）
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(LLAMA_WERRORS         OFF CACHE BOOL "" FORCE)

# =====================================================
# 把 llama.cpp 作为子目录拉进来
# —— 会产出 4 个 static lib target：ggml-base、ggml-cpu、ggml、llama
#    （b4835 版本 ggml 拆分了 base/cpu 两级架构；LLAMA_BUILD_EXAMPLES=OFF，
#     因此 common 子模块不编，链接时不能写 common，否则 ld 找不到 -lcommon）
# =====================================================
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama_build)

# =====================================================
# M5-2 占位验证：llama_jni_stub.cpp → libxuedi-llama.so
#   只做 JNI_OnLoad，确认 Gradle+NDK+CMake+链接llama静态库整个链路能过。
# M5-4 会把这个源码替换成真正的 llama_jni.cpp（真tokenize/batch/decode）
# =====================================================
add_library(xuedi-llama SHARED
    llama_jni_stub.cpp
)

target_include_directories(xuedi-llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/common
)

# 注意：target_link_libraries 里严禁再写 common！
#      b4835 + LLAMA_BUILD_EXAMPLES=OFF 时该 target 不存在。
target_link_libraries(xuedi-llama PRIVATE
    llama
    ggml
    ggml-cpu
    ggml-base
    log          # Android NDK __android_log_print
    z            # 部分 llama.cpp 版本可能需要
)

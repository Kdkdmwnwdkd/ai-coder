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
# 方案A v1.3.26-gpu1：Vulkan GPU 加速（魅族 20 = 骁龙 8 Gen 2 = Adreno 740，Android 13 Vulkan 1.3 必带）
# 安全策略（与方案A用户要求一致：无 Vulkan 不阻塞 CPU 构建）：
#   · find_package(Vulkan) 失败 → GGML_VULKAN=OFF，CPU-only 构建（perf2-thread4 底包不变）
#   · 成功 → GGML_VULKAN=ON，ggml-vulkan 目标通过 add_subdirectory 自动参与编译；
#            ggml-vulkan 内的 shader 生成在 CMAKE_CROSSCOMPILING 时会走
#            ExternalProject vulkan-shaders-gen（自动探测 host gcc/clang），
#            GitHub Actions ubuntu-latest 默认 gcc+glslc 齐全，无坑。
#   · 给 JNI 传编译宏 XUEDI_LLAMA_VULKAN=1/0，llama_jni.cpp 里据此决定
#     n_gpu_layers 实际允许值（CPU-only 编译即使 Kotlin 传 -1 也会夹到 0，避免零收益）。
#
# 🔥 2026-09-03 v1.3.26-gpu1-hotfix1：临时强令 XUEDI_HAS_VULKAN=OFF 再跑一次 Actions。
#    原因：刚 push 的 tag run(#220) / main run(#219) 35 分钟还 In progress，
#          正常 build 5min，慢 7× 说明 Vulkan 分支 + glslang shader 生成
#          在 Actions runner 上第一次拉源码编译时拖到 20+min。
#    为让方案 C 设置页开关 + App 层预热逻辑先装包验证（这两个不依赖 Vulkan，
#    且 JNI 里 XUEDI_LLAMA_VULKAN=0 时 n_gpu_layers 100% 被钳到 0，行为 = perf2-thread4），
#    先出一个 CPU 底包 APK 让用户测 UI。
#    🔓 以后想启用 Vulkan A/B，把下面一行注释掉，恢复 find_package(Vulkan) 即可。
# =====================================================
set(XUEDI_HAS_VULKAN OFF CACHE BOOL "" FORCE)
# find_package(Vulkan)
if (0) # if (Vulkan_FOUND)
    message(STATUS "[xuedi-llama] Vulkan FOUND (include=${Vulkan_INCLUDE_DIRS} libs=${Vulkan_LIBRARIES} glslc=${Vulkan_GLSLC_EXECUTABLE}) → 启用 ggml-vulkan")
    set(GGML_VULKAN                       ON  CACHE BOOL "" FORCE)
    set(GGML_VULKAN_DEBUG                 OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_CHECK_RESULTS         OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_MEMORY_DEBUG          OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_SHADER_DEBUG_INFO     OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_PERF                  OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_VALIDATE              OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN_RUN_TESTS             OFF CACHE BOOL "" FORCE)
    set(XUEDI_HAS_VULKAN ON)
else ()
    message(STATUS "[xuedi-llama] Vulkan TEMPORARILY DISABLED by v1.3.26-gpu1-hotfix1 → 保持 CPU-only 构建（= perf2-thread4 底包）")
    set(GGML_VULKAN                       OFF CACHE BOOL "" FORCE)
endif ()

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

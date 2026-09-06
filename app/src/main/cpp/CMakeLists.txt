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
# =====================================================
# code278：Vulkan 真启用（撤掉 v1.3.26-gpu1-hotfix1 的临时关闭）。
#   · find_package(Vulkan)：minSdk=26 ≥ 24，Android NDK sysroot 自带
#     vulkan/vulkan.h + aarch64 libvulkan.so stub，交叉编译时直接命中；
#     设备上真正的 libvulkan.so 由 Adreno 驱动提供（骁龙 8 Gen2 必带 Vulkan 1.3）。
#   · shader 编译靠 HOST 的 glslc（Google shaderc，apt 包名 glslc），
#     build.yml 已加安装步骤；vulkan-shaders-gen 本体只是个小 C++ 工具（秒级编译）。
#   · 找不到 Vulkan → GGML_VULKAN=OFF 纯 CPU 构建，行为与之前完全一致（永不阻塞出包）。
#   · 运行时兜底：设备上 Vulkan 初始化失败 → LlamaJniEngine.loadModelRobust 检测
#     Vulkan 关键词后自动把 gpuHint 切 0，降级档位强制 CPU；用户也可在设置里关。
# =====================================================
# code278-b10819：升级 b5180 → b10819 后，ggml-vulkan/CMakeLists.txt 顶部新增
#   find_package(SPIRV-Headers CONFIG REQUIRED)（ggml-vulkan.cpp 要
#   #include <spirv/unified1/spirv.hpp> 做 SPIRV 二进制补丁，b5180 没这段）。
#   该 find_package 跑在 Android 交叉上下文里，NDK sysroot 没有 SPIRV-Headers，
#   必须显式指向 HOST 的 Ubuntu spirv-headers 包（build.yml 已 apt 安装）：
#     · cmake config: /usr/lib/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake
#     · 头文件:       /usr/include/spirv/unified1/spirv.hpp
#   注意 llama.cpp 只 find_package 却从不 link SPIRV-Headers::SPIRV-Headers，
#   所以头文件目录要我们在 add_subdirectory 之后手动补到 ggml-vulkan target。
#   config / 头文件任一缺失 → 与 Vulkan NOT FOUND 同策略：CPU-only，永不阻塞出包。
# =====================================================
set(XUEDI_SPIRV_OK OFF)
if (Vulkan_FOUND)
    # NO_CMAKE_FIND_ROOT_PATH：Android toolchain 把 find_* 限制在 sysroot，
    # 必须显式放开才能搜 HOST 路径（与 ggml detect_host_compiler 同一招）
    # Ubuntu 24.04 spirv-headers 实际装到 /usr/share/cmake/SPIRV-Headers（沙箱实测），
    # 其余几个路径是其他发行版/源码安装的常见位置，一并兜底。
    find_file(XUEDI_SPIRV_HEADERS_CONFIG
        NAMES SPIRV-HeadersConfig.cmake
        PATHS /usr/share/cmake/SPIRV-Headers
              /usr/lib/cmake/SPIRV-Headers
              /usr/local/lib/cmake/SPIRV-Headers
              /usr/lib/x86_64-linux-gnu/cmake/SPIRV-Headers
        NO_CMAKE_FIND_ROOT_PATH)
    find_path(XUEDI_SPIRV_INCLUDE_DIR
        NAMES spirv/unified1/spirv.hpp
        PATHS /usr/include /usr/local/include
        NO_CMAKE_FIND_ROOT_PATH)
    if (XUEDI_SPIRV_HEADERS_CONFIG AND XUEDI_SPIRV_INCLUDE_DIR)
        # 预置 <Pkg>_DIR：find_package 优先吃这个显式路径，不受 sysroot re-root 影响
        get_filename_component(XUEDI_SPIRV_HEADERS_DIR "${XUEDI_SPIRV_HEADERS_CONFIG}" DIRECTORY)
        set(SPIRV-Headers_DIR "${XUEDI_SPIRV_HEADERS_DIR}" CACHE PATH "host SPIRV-Headers cmake dir (xuedi)" FORCE)
        set(XUEDI_SPIRV_OK ON)
    endif ()
endif ()

if (Vulkan_FOUND AND XUEDI_SPIRV_OK)
    message(STATUS "[xuedi-llama] Vulkan FOUND (include=${Vulkan_INCLUDE_DIRS} libs=${Vulkan_LIBRARIES} glslc=${Vulkan_GLSLC_EXECUTABLE}) + SPIRV-Headers(${XUEDI_SPIRV_HEADERS_DIR}) → 启用 ggml-vulkan")
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
    if (Vulkan_FOUND AND NOT XUEDI_SPIRV_OK)
        message(STATUS "[xuedi-llama] Vulkan 在，但 HOST 缺 spirv-headers（CI 应 apt install spirv-headers）→ CPU-only 构建")
    else ()
        message(STATUS "[xuedi-llama] Vulkan NOT FOUND → CPU-only 构建（= perf2-thread4 底包）")
    endif ()
    set(GGML_VULKAN                       OFF CACHE BOOL "" FORCE)
    set(XUEDI_HAS_VULKAN OFF)
endif ()

set(LLAMA_ARM_NEON        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_NEON     ON  CACHE BOOL "" FORCE)

# 避免 llama.cpp 子工程把 WARNING 当 ERROR
set(LLAMA_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(LLAMA_WERRORS         OFF CACHE BOOL "" FORCE)

# =====================================================
# 把 llama.cpp 作为子目录拉进来

# 🔥 强制 native 代码始终用 Release(-O3) 优化，不管 Gradle 是 debug 还是 release 构建
#    用户实测：O3 比 debug 默认的 O0/O1 推理速度快数倍
set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
# 会产出 4 个 static lib target：ggml-base、ggml-cpu、ggml、llama
# =====================================================
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama_build)

# code278-b10819: ggml-vulkan.cpp 要 <spirv/unified1/spirv.hpp>，但 llama.cpp 只
# find_package(SPIRV-Headers) 从不 link 它 → include 目录我们自己补（HOST 头只含
# spirv/ 子目录纯常量定义，不会污染 Android sysroot 头搜索）。
if (XUEDI_HAS_VULKAN AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE ${XUEDI_SPIRV_INCLUDE_DIR})
    message(STATUS "[xuedi-llama] ggml-vulkan 补 SPIRV include: ${XUEDI_SPIRV_INCLUDE_DIR}")
endif ()

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

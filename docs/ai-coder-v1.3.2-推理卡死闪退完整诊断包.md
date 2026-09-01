# AI编程助手 v1.3.2 — 推理卡死/闪退完整诊断包

> 打包时间：2026-09-01
> 目标机型：魅族 20（骁龙 8 Gen2 · 12GB RAM · arm64-v8a）
> 模型：Qwen2.5-3B-Instruct Q4_K_M GGUF（2.1GB）
> 现象：点发送后 20~36s 内直接闪退（不走到 45s 超时逻辑），AI 一直转圈没回答
> 附带好消息：主题模式 Chip（浅色/深色/跟随系统）已在 v1.3.2 设置页生效

---

## 一、复现链路（从用户操作到闪退）

```
用户点发送（聊天页 ChatPage）
  └─ ChatViewModel.sendMessage(system, user)   (ChatViewModel.kt)
       └─ LlamaEngineHolder.engine.chatFlow(system, user)  ← 引擎就是 LlamaJniEngine
            └─ callbackFlow {
                 launch(Dispatchers.Default) { nativeChat(curCtx, system, user, cb) }
                 launch(Dispatchers.Default) { delay(45_000); if (!firstTokenReceived) { cancel(); send(Error) } }
               }

nativeChat 内部（C++ llama_jni.cpp）：
  1) 取 system+user → 拼 ChatML prompt（<|im_start|>system...<|im_start|>assistant）
  2) llama_tokenize → 返回 n_prompt 个 token（若场景全开会有 500-800 token）
  3) 预填充 while(n_consumed < n_prompt)  { llama_decode(batch of n_batch=512) }
  4) 生成循环 while(n_generated < 1024) { sample → token_to_piece → cb_token → llama_decode }

闪退发生阶段：第 3 步 预填充 llama_decode（概率最高）
  或 第 4 步 早期 llama_decode（内存还没被 kv_cache 占满时）
```

---

## 二、所有代码文件（按依赖顺序列出）

### 2.1 构建配置

```kotlin
// app/build.gradle.kts（节选 version + buildFeatures）
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xuedi.coder"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xuedi.coder"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "2.3.0-M12-TRAE-Fix"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### 2.2 CMakeLists.txt（构建 .so）

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("xuedi-llama")

# ===== llama.cpp 源码（git submodule，版本 b4835）=====
set(LLAMA_DIR ${CMAKE_SOURCE_DIR}/llama.cpp)
include_directories(${LLAMA_DIR}/include ${LLAMA_DIR}/src ${LLAMA_DIR}/common)

# 编译 llama.cpp 作为静态库（CPU-only，禁用所有 backend）
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_ACCELERATE OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL OFF CACHE BOOL "" FORCE)
set(LLAMA_CUDA OFF CACHE BOOL "" FORCE)
set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_BLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_RPC OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama)

# ==== 我们的 JNI bridge ====
add_library(xuedi-llama SHARED llama_jni.cpp)
find_library(log-lib log)
target_link_libraries(xuedi-llama PRIVATE llama ${log-lib})
target_compile_options(xuedi-llama PRIVATE -O3 -fvisibility=hidden)
```

### 2.3 App.kt（单例 + engine 初始化）

```kotlin
package com.xuedi.coder

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.xuedi.coder.data.ModelDatabase
import com.xuedi.coder.model.LlamaEngineHolder
import com.xuedi.coder.plugin.PluginDatabase
import com.xuedi.coder.plugin.PluginManager
import com.xuedi.coder.theme.ThemeStore
import kotlinx.coroutines.launch

class App : Application() {
    lateinit var themeStore: ThemeStore
        private set
    lateinit var modelDatabase: ModelDatabase
        private set
    lateinit var pluginDatabase: PluginDatabase
        private set
    lateinit var pluginManager: PluginManager
        private set

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        themeStore = ThemeStore(this)
        modelDatabase = ModelDatabase.build(this)
        pluginDatabase = PluginDatabase.build(this)
        pluginManager = PluginManager(pluginDatabase.pluginDao())
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            pluginManager.ensureSeedIfEmpty()
            // 启动时异步预热 LlamaJniEngine（先 System.loadLibrary）
            runCatching { LlamaEngineHolder.engine }
        }
    }
}
```

### 2.4 LlmEngine.kt（接口定义）

```kotlin
package com.xuedi.coder.model

import kotlinx.coroutines.flow.Flow

sealed interface ChatChunk {
    data class Token(val piece: String) : ChatChunk
    data class Done(val fullText: String, val stopReason: String) : ChatChunk
    data class Error(val cause: Throwable?, val message: String) : ChatChunk
    data object StatusPrefilling : ChatChunk
    data object StatusGenerating : ChatChunk
}

interface LlmEngine {
    fun loadModel(ggufAbsolutePath: String, nCtx: Int = 4096, nThreads: Int = 4, nGpuLayers: Int = 0): Boolean
    fun chatFlow(system: String, user: String): Flow<ChatChunk>
    fun release()
    fun cancel()
}
```

### 2.5 LlamaEngineHolder.kt（全局 engine 单例）

```kotlin
package com.xuedi.coder.model

object LlamaEngineHolder {
    val engine: LlmEngine by lazy { LlamaJniEngine() }
}
```

### 2.6 LlamaJniEngine.kt（完整文件，JNI 封装 + chatFlow）

> 注：以下为 v1.3.2 当前提交 e8ec503 的内容

```kotlin
package com.xuedi.coder.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class LlamaJniEngine : LlmEngine {

    companion object {
        private const val TAG = "LlamaJniEngine"
        @Volatile private var libLoaded: Boolean? = null
        @Volatile private var libLoadError: String? = null

        fun ensureLibLoaded(): Boolean = synchronized(this) {
            libLoaded?.let { return it }
            val result = runCatching {
                System.loadLibrary("xuedi-llama")
                Log.i(TAG, "System.loadLibrary(\"xuedi-llama\") ✅")
                true
            }
            libLoadError = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            val ok = result.getOrDefault(false)
            libLoaded = ok
            ok
        }
        fun libStatus(): Pair<Boolean?, String?> = libLoaded to libLoadError
    }

    @Volatile private var ctx: Long = 0L
    @Volatile private var lastLoadError: String? = null
    fun currentCtx(): Long = ctx
    fun lastLoadError(): String? = lastLoadError

    private val fallbackLock = Any()
    @Volatile private var fallback: MockLlmEngine? = null
    private fun mkFallbackIfNeed(): MockLlmEngine = fallback
        ?: synchronized(fallbackLock) {
            fallback ?: run { MockLlmEngine().also { fallback = it } }
        }

    init { ensureLibLoaded() }

    private interface TokenCallback {
        fun onToken(piece: String)
        fun onDone(reason: String)
        fun onError(message: String)
    }

    // =====================================================================
    // loadModel：闪退修复 v2（关键！）
    //   loadModel 前只 cancel 旧 nativeChat + 清 ctx = 0，
    //   不立即 release。原因：nativeChat 是阻塞 JNI while 循环（CPU 密集 llama_decode），
    //   cancel 只是置 std::atomic flag，decode 可能正卡在矩阵计算里几十毫秒没判断 flag。
    //   如果此时立即 nativeRelease（delete state），旧 nativeChat 下一次解引用 LlamaState*
    //   → SIGSEGV 闪退。正确做法：invokeOnCompletion 里 nativeChat 真结束后 release。
    // =====================================================================
    fun loadModel(ggufAbsolutePath: String, nCtx: Int = 4096, nThreads: Int = 4, nGpuLayers: Int = 0): Boolean {
        lastLoadError = null
        val libOk = ensureLibLoaded()
        if (!libOk) { lastLoadError = "JNI lib 加载失败"; return false }
        if (ggufAbsolutePath.isBlank()) { lastLoadError = "模型文件路径为空"; return false }
        val f = java.io.File(ggufAbsolutePath)
        if (!f.exists() || !f.isFile) { lastLoadError = "模型文件不存在：$ggufAbsolutePath"; return false }
        if (f.length() < 1024 * 1024) { lastLoadError = "模型文件过小"; return false }

        // 只 cancel + 清 ctx，不 release
        val oldCtx = ctx
        if (oldCtx != 0L) {
            runCatching { nativeChatCancel(oldCtx) }
            ctx = 0L
            Log.i(TAG, "loadModel: 旧 ctx=$oldCtx 已 cancel + ctx 置 0")
        }

        val newCtx = runCatching { nativeInit(ggufAbsolutePath, nCtx, nThreads, nGpuLayers) }
            .getOrElse { t -> lastLoadError = "nativeInit 抛异常：${t.message}"; 0L }
        if (newCtx == 0L) {
            if (lastLoadError == null) lastLoadError = "nativeInit 返回 ctx=0"
            return false
        }
        ctx = newCtx
        Log.i(TAG, "loadModel ✅ ctx=$ctx；size=${f.length()/1024/1024}MB")
        lastLoadError = null
        return true
    }

    // =====================================================================
    // chatFlow：首 token 45s 超时 + 并发 ctx 校验
    // =====================================================================
    override fun chatFlow(system: String, user: String): Flow<ChatChunk> {
        val libOk = ensureLibLoaded()
        val curCtx = ctx
        if (!libOk) {
            val (_, libErr) = libStatus()
            return flowOf(ChatChunk.Error(RuntimeException(libErr?:"lib load failed"),
                "❌ JNI 引擎未就绪\n原因：$libErr"))
        }
        if (curCtx == 0L) {
            val diag = lastLoadError ?: "模型尚未加载或加载失败，ctx=0"
            return flowOf(ChatChunk.Error(RuntimeException(diag),
                "❌ 模型未加载成功\n诊断：$diag"))
        }
        return callbackFlow {
            val fullSb = StringBuilder()
            val firstTokenReceived = java.util.concurrent.atomic.AtomicBoolean(false)

            // 阻塞 JNI 在 Default 协程池跑（切主线程会 ANR）
            val job = launch(Dispatchers.Default) {
                val cb = object : TokenCallback {
                    override fun onToken(piece: String) {
                        // 🔴 ctx 变了 → 旧推理回调丢弃（防 SIGSEGV）
                        if (this@LlamaJniEngine.ctx != curCtx) return
                        firstTokenReceived.set(true)
                        fullSb.append(piece)
                        trySend(ChatChunk.Token(piece))
                    }
                    override fun onDone(reason: String) {
                        if (this@LlamaJniEngine.ctx != curCtx) { channel.close(); return }
                        trySend(ChatChunk.Done(fullSb.toString(), reason))
                        channel.close()
                    }
                    override fun onError(message: String) {
                        if (this@LlamaJniEngine.ctx != curCtx) { channel.close(); return }
                        trySend(ChatChunk.Error(RuntimeException(message), message))
                        channel.close()
                    }
                }
                val ok = runCatching { nativeChat(curCtx, system, user, cb) }
                if (ok.isFailure) {
                    val t = ok.exceptionOrNull()
                    Log.e(TAG, "nativeChat 异常：${t?.message}")
                    channel.close()
                    mkFallbackIfNeed().chatFlow(system, user).collect { send(it) }
                }
            }
            // 🔴 首 token 45s 超时（之前 15s，3B 模型 500 token prefill 手机 CPU 要 20-30s）
            val timeoutJob = launch(Dispatchers.Default) {
                delay(45_000L)
                if (!firstTokenReceived.get()) {
                    Log.w(TAG, "chatFlow 首 token 超时(45s)")
                    runCatching { nativeChatCancel(curCtx) }
                    trySend(ChatChunk.Error(RuntimeException("首 token 超时(45s)"),
                        "首 token 超时(45s)：可能场景插件太多或内存紧张。建议减少场景开关数量或重启手机。"))
                    channel.close()
                }
            }
            job.invokeOnCompletion { cause ->
                timeoutJob.cancel()
                runCatching { nativeChatCancel(curCtx) }
                // 🔴 闪退修复 v2：如果 ctx 被替换或清零 → 释放旧 state 防内存泄漏
                if (this@LlamaJniEngine.ctx != curCtx) {
                    runCatching { nativeRelease(curCtx) }
                    Log.i(TAG, "invokeOnCompletion: 释放已替换的旧 ctx=$curCtx")
                }
                cause?.let { Log.w(TAG, "chatFlow cancel：${it.message}") }
            }
            awaitClose {
                timeoutJob.cancel()
                runCatching { nativeChatCancel(curCtx) }
            }
        }
    }

    override fun release() {
        if (ctx != 0L) runCatching {
            nativeChatCancel(ctx)
            Thread.sleep(30)
            nativeRelease(ctx)
        }.onFailure { Log.w(TAG, "nativeRelease 异常：${it.message}") }
        ctx = 0L
        runCatching { fallback?.release() }
    }
    override fun cancel() {
        if (ctx != 0L) runCatching { nativeChatCancel(ctx) }
        runCatching { fallback?.cancel() }
    }

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    private external fun nativeRelease(ctx: Long)
    private external fun nativeChat(ctx: Long, system: String, user: String, callback: TokenCallback)
    private external fun nativeChatCancel(ctx: Long)
}
```

### 2.7 llama_jni.cpp（完整文件，JNI bridge）

> 注：llama.cpp 版本 b4835（commit b4835 在 llama.cpp 仓库）。所有 API 严格按 llama.h 最新版本写。

```cpp
// 完整代码见 app/src/main/cpp/llama_jni.cpp（477 行）
// 核心结构：
struct LlamaState {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t n_vocab = 0;
    int32_t n_ctx   = 0;
    int32_t n_batch = 0;
    std::atomic<bool> cancel{false};   // 取消标志
    ~LlamaState() {
        cancel.store(true);
        if (ctx)   { llama_free(ctx);   ctx   = nullptr; }
        if (model) { llama_model_free(model); model = nullptr; }
    }
};

// nativeInit: llama_model_load_from_file → llama_init_from_model → return state ptr as jlong
// nativeRelease: delete state
// nativeChatCancel: state->cancel.store(true)
// nativeChat:
//   1) 拼 ChatML prompt（<|im_start|>system/user/assistant）
//   2) llama_tokenize（分两步：先估算再真分，need 可能为负 = abs 后是大小）
//   3) 预填充 while(n_consumed < n_prompt) {
//        if (state->cancel.load()) break;
//        n_eval = min(n_prompt-n_consumed, n_batch=512);
//        batch = llama_batch_get_one(tokens+n_consumed, n_eval);
//        llama_decode(state->ctx, batch);
//        llama_batch_free(batch);
//        n_consumed += n_eval;
//      }
//   4) 生成循环 while(n_generated < 1024) {
//        if (state->cancel.load()) break;
//        id = llama_sampler_sample(sampler, state->ctx, -1);
//        llama_sampler_accept(sampler, id);
//        if (id == eos) break;
//        llama_token_to_piece → cb_token
//        batch = llama_batch_get_one(&id, 1);
//        llama_decode(state->ctx, batch);
//        llama_batch_free(batch);
//        n_consumed++; n_generated++;
//      }
```

### 2.8 ChatViewModel.kt（状态管理 + 互斥锁）

```kotlin
package com.xuedi.coder.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xuedi.coder.App
import com.xuedi.coder.data.ChatDatabase
import com.xuedi.coder.data.ChatMsgEntity
import com.xuedi.coder.model.ChatChunk
import com.xuedi.coder.model.LlamaEngineHolder
import com.xuedi.coder.plugin.PluginManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class InferenceState { IDLE, PREFILL, GENERATING, ERROR, DONE }

data class ChatUiState(
    val messages: List<ChatMsgEntity> = emptyList(),
    val state: InferenceState = InferenceState.IDLE,
    val errorMessage: String? = null,
    val streamingAssistantText: String = ""
)

class ChatViewModel(
    private val chatDb: ChatDatabase = App.instance.let { ChatDatabase.build(it) },
    private val pluginManager: PluginManager = App.instance.pluginManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // 🔴 互斥锁：防止上一次推理还没结束又点发送 → ctx 并发
    private val inferenceMutex = Mutex()
    private var currentJob: Job? = null

    fun sendMessage(userText: String, topicId: Long = 0) = viewModelScope.launch {
        // 1. 先插用户消息
        val userMsg = ChatMsgEntity(
            topicId = topicId, role = "user", content = userText,
            timestampMs = System.currentTimeMillis()
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMsg,
            state = InferenceState.PREFILL,
            errorMessage = null,
            streamingAssistantText = ""
        )
        runCatching { chatDb.chatDao().insert(userMsg) }

        // 2. 取消上一次
        currentJob?.cancel()
        runCatching { LlamaEngineHolder.engine.cancel() }

        // 3. 拼 system prompt（已启用的场景）
        val system = pluginManager.buildSystemPromptForEnabled()

        // 4. mutex 保护推理
        currentJob = launch {
            inferenceMutex.withLock {
                LlamaEngineHolder.engine.chatFlow(system, userText).collect { chunk ->
                    when (chunk) {
                        ChatChunk.StatusPrefilling ->
                            _uiState.value = _uiState.value.copy(state = InferenceState.PREFILL)
                        is ChatChunk.Token -> {
                            _uiState.value = _uiState.value.copy(
                                state = InferenceState.GENERATING,
                                streamingAssistantText = _uiState.value.streamingAssistantText + chunk.piece
                            )
                        }
                        is ChatChunk.Done -> {
                            val final = chunk.fullText.ifBlank { _uiState.value.streamingAssistantText }
                            val aiMsg = ChatMsgEntity(
                                topicId = topicId, role = "assistant", content = final,
                                timestampMs = System.currentTimeMillis(),
                                stopReason = chunk.stopReason
                            )
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + aiMsg,
                                state = InferenceState.DONE,
                                streamingAssistantText = ""
                            )
                            runCatching { chatDb.chatDao().insert(aiMsg) }
                        }
                        is ChatChunk.Error -> {
                            _uiState.value = _uiState.value.copy(
                                state = InferenceState.ERROR,
                                errorMessage = chunk.message
                            )
                        }
                        ChatChunk.StatusGenerating ->
                            _uiState.value = _uiState.value.copy(state = InferenceState.GENERATING)
                    }
                }
            }
        }
    }

    fun stopGeneration() = viewModelScope.launch {
        currentJob?.cancel()
        runCatching { LlamaEngineHolder.engine.cancel() }
        _uiState.value = _uiState.value.copy(state = InferenceState.IDLE)
    }
}
```

### 2.9 ChatPage.kt（聊天页 UI 节选：状态条 + 取消按钮）

```kotlin
// 状态条：IDLE 隐藏；PREFILL 显示"正在准备..."；GENERATING 显示"生成中"；ERROR 显示红色错误条
val uiState by vm.uiState.collectAsState()
val state = uiState.state
if (state == InferenceState.PREFILL) {
    StatusChip(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        state = "正在加载模型并处理问题（可能需要 20~40 秒，请勿退出）...",
        accent = MaterialTheme.colorScheme.primary,
        progress = null,  // 无限转圈
        onCancel = { vm.stopGeneration() }
    )
} else if (state == InferenceState.GENERATING) {
    StatusChip(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        state = "AI 回答中...",
        accent = MaterialTheme.colorScheme.primary,
        progress = null,
        onCancel = { vm.stopGeneration() }
    )
} else if (state == InferenceState.ERROR && !uiState.errorMessage.isNullOrBlank()) {
    // 🔴 错误条：红色背景 + 关闭按钮
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
            )
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(
                    uiState.errorMessage!!,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp
                )
                IconButton(onClick = { vm.stopGeneration() }) {
                    Icon(Icons.Default.Close, null)
                }
            }
        }
    }
}
```

### 2.10 PluginManager.kt（system prompt 拼接）

```kotlin
package com.xuedi.coder.plugin

import com.xuedi.coder.data.PluginDao
import com.xuedi.coder.data.PluginEntity
import kotlinx.coroutines.flow.Flow

class PluginManager(private val dao: PluginDao) {

    suspend fun ensureSeedIfEmpty() {
        if (dao.count() == 0) {
            PluginConfig.DEFAULT.forEach { dao.upsert(it) }
        }
    }

    fun allEnabledFlow(): Flow<List<PluginEntity>> = dao.observeAll()

    suspend fun toggle(id: String, enabled: Boolean) = dao.toggle(id, enabled)

    /**
     * 把所有「已启用」场景的 system prompt 拼起来。
     * 根因：4 个场景全开 → 500~800 token → 手机 CPU 预填充 20-30s → 超时或闪退
     */
    fun buildSystemPromptForEnabled(): String {
        val enabled = runCatching { dao.getAll().filter { it.enabled } }.getOrDefault(emptyList())
        if (enabled.isEmpty()) {
            return "You are a helpful coding assistant. Write concise, correct code."
        }
        return buildString {
            append("You are a multi-domain AI coding assistant. The following specialties are currently ENABLED. ").appendLine()
            append("For each enabled domain, inject the corresponding expertise into your responses:").appendLine().appendLine()
            enabled.forEach {
                append("## ").appendLine(it.name)
                append(it.systemPrompt).appendLine().appendLine()
            }
            append("## 输出要求\n")
            append("- 中文回答，英文用 code block 输出代码\n")
            append("- 代码需要直接可运行（注释用中文解释）\n")
            append("- 不重复解释基础知识，默认用户有项目背景\n")
        }
    }
}
```

### 2.11 SettingsPage.kt 主题模式部分（确认生效）

```kotlin
// 3 个主题模式 Chip，写在 AppearanceCard 顶部
Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    ThemeModeChip("浅色", themeMode == ThemeMode.LIGHT) {
        scope.launch { themeStore.setThemeMode(ThemeMode.LIGHT) }
    }
    ThemeModeChip("深色", themeMode == ThemeMode.DARK) {
        scope.launch { themeStore.setThemeMode(ThemeMode.DARK) }
    }
    ThemeModeChip("跟随系统", themeMode == ThemeMode.FOLLOW_SYSTEM) {
        scope.launch { themeStore.setThemeMode(ThemeMode.FOLLOW_SYSTEM) }
    }
}
```

---

## 三、已踩过的坑（重要！供 DeepSeek 参考）

### 坑 1：llama_tokenize 返回负值 = 估算 buffer 大小，不是错误

- **现象**：llama_tokenize(vocab, prompt, len, nullptr, 0, ...) 返回 -1214，代码把负值当失败 → 分配小 buffer → 第二次 tokenize 还返回同样负值 → 给用户报错"tokenizer 损坏"
- **官方语义**：第一次传 buffer=null 时，返回值无论正负都是「需要的 token 个数」的编码，`abs(need)` 才是真实数。只有第二次传非空 buffer 返回负值才是真错误。
- **修复**：`est = (need > 0) ? need : (-need)`

### 坑 2：nativeChat 必须跑在 Dispatchers.Default，不能主线程

- **现象**：ANR "应用无响应" 5 秒
- **原因**：nativeChat 是阻塞 JNI（C++ while 循环几分钟 CPU 密集），Compose callbackFlow 默认继承主线程协程
- **修复**：`launch(Dispatchers.Default) { nativeChat(...) }`

### 坑 3：loadModel 不能 nativeRelease 太早

- **现象**：设置页点「重新加载到内存」闪退
- **原因**：loadModel 里 cancel → sleep(50) → release。cancel 只置 atomic flag，nativeChat 正卡 llama_decode 矩阵乘法里（一次解码 200-500ms），sleep(50) 不够，还没判断 flag 就 delete state → SIGSEGV
- **修复**：loadModel 只 cancel + 清 ctx，旧 state 在 invokeOnCompletion（nativeChat 真结束）里释放

### 坑 4：首 token 超时 15s 太短

- **现象**：4 个场景全开 → 20~30s 正常预填充被超时杀
- **修复**：15s→45s

### 坑 5：onToken/onDone/onError 回调要判断 ctx 是否变了

- **现象**：新推理跑着，旧推理的回调还在来 → 错误 token 显示
- **修复**：`if (this@LlamaJniEngine.ctx != curCtx) return`

### 坑 6：ChatML template 末尾必须有 `<|im_start|>assistant` 空行

- **坑**：如果没写，模型会生成"<|im_start|>assistant\n你好..."这样的错误文本（在代码里带模板标记）
- **修复**：固定 prompt = `<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n`

### 坑 7：sdkmanager --install 必须 yes \| 前缀（接受 license）

- **CI 坑**：ubuntu-24.04 runner 没预装 NDK/CMake，`yes | sdkmanager --install ndk;cmake;3.22.1`
- 不然会 interactive 阻塞 30 分钟等 Y/N

### 坑 8：Debug keystore 固定（不然覆盖安装失败）

- **现象**：每次 CI 新 keystore → SHA1 不同 → 用户覆盖安装"签名不一致需卸载"
- **修复**：keystore 转 base64 存 GitHub Secret，构建时 decode 成文件，signingConfigs 固定密码/alias

### 坑 9：C++ callback 前必须 AttachCurrentThread

- **现象**：No JNIEnv* 崩溃
- **修复**：`ensure_env()` — 用 `g_vm->AttachCurrentThread(&env, nullptr)`

### 坑 10：JNI_OnLoad 缓存的 methodIDs 必须 NewGlobalRef

- **坑**：只 local ref → 类卸载 → methodID 失效 → callback NoSuchMethodError
- **修复**：`g_cls_Callback = (jclass)env->NewGlobalRef(localCls)`

---

## 四、当前问题深度分析（还没解决的）

### 问题 1：20~36s 直接闪退（不走到 45s 超时）

**最高概率根因排序：**

#### A. 内存不足（OOM）→ LlamaState 的 new/malloc 返回 null → 段错误
- 模型 Q4_K_M 2.1GB 本身就在磁盘，但 llama_init_from_model 需要连续虚拟地址分配 kv_cache + 权重页表
- 魅族 20 12GB RAM：系统/微信/浏览器/后台 App 占 3-4GB，剩余可用虚拟内存可能只有 6-7GB
- llama 初始化 + 第一次 prefill decode 需要内存峰值（200-300 token → 激活层大）
- OOM 的典型特征：时间跟内存占用正相关（第 1 次 20s 还没撑过 prefill 就崩；第 2 次 36s 可能是 prefill 刚结束，生成第几个 token 时激活层 OOM）
- **排查**：设置页关 3 个场景（只剩 1 个）+ 重启手机 + 关所有后台后再试。如果能回答就 100% OOM。

#### B. llama_tokenize 后 tokens 数量真的超 n_ctx-16
- 4 场景 + "你好" system prompt 可能有 600-800 token（含 template）
- nCtx=4096 看起来够，但如果 PluginManager 拼 prompt 有重复追加 bug → 3000+ token
- **排查**：logcat 过滤 `LlamaJNI` 看 `nativeChat: prompt tokenized n_prompt=?` 这一行有没有超 4080

#### C. generate 阶段的 `llama_batch_free(batch)` 没有正确释放
- 虽然代码里每个 decode 后都 free，但如果 llama_sampler_accept 的 penalty 上下文和 batch 内 token 不一致 → 内部 use-after-free
- **低概率，但可能**

#### D. C++ 层 `ensure_env()` 返回 null → cb_error 中 env=null 但还调 callback
- 看代码 cb_error/env=null 直接 return，不会 crash。排除。

#### E. PluginManager 拼 system prompt 抛 OOM（字符串拼太大）
- 低概率，但建议加 `buildSystemPromptForEnabled()` 前先判字符长度上限

### 问题 2：转圈圈一直没有回答

**最高概率根因：**

#### A. 闪退 = 回答没回来 → 聊天页状态停在 PREFILL/GENERATING
- 所以用户看到的是"一直转"，其实 APP 崩了又自动回前台（单 Activity 架构）
- **验证**：logcat 有没有 `FATAL EXCEPTION` / `libc: Fatal signal 11 (SIGSEGV)`

#### B. 预填充真的慢，不是 bug
- 4 场景（800 token）+ 骁龙 8 Gen2 单核效能 CPU prefill 速度 ≈ 25-35 tok/s → 800/30 ≈ 27s，正好 20-36s 范围
- 如果能出第一个 token 后回答速度正常（6-12 tok/s）→ 就是正常
- **验证**：设置页关场景，只剩 1 个或全关，再发短问题（如"你好"）→ 8s 内能回答就是场景多 prompt 长导致

---

## 五、建议 DeepSeek 一起排查的 5 个修改建议

1. **OOM 保险：** LlamaJniEngine 里加 `ActivityManager.getMemoryInfo()` 判断可用内存 < 4GB 就直接 Error（不要跑 llama_decode），给用户明确提示
2. **预填充进度回调：** C++ 预填充 while 循环每次 n_consumed += n_eval 后，给 Java 发 `StatusPrefilling(n_consumed*100/n_prompt)` 进度百分比，UI 显示"正在准备 32%..."不要一直白转
3. **生成循环加 try/catch：** C++ decode 阶段套 `try{}catch(...){ cb_error("native decode crashed") }`（虽然 C++ 异常默认关闭，但如果是 Android linker 信号处理也能抓）
4. **场景默认值：** PluginConfig.DEFAULT 里所有场景默认 `enabled=false`，首次安装后用户只开 1 个，不会 4 个一起开导致 OOM
5. **日志上传：** build.gradle 里开 `isDebuggable=true`（或者 release 加 `debuggable true` + `android:debuggable`）让用户能用 logcat 抓 SIGSEGV 栈

---

## 六、文件清单（给 DeepSeek 的具体路径）

```
/workspace/ai-coder/
├── app/build.gradle.kts                                    ← 版本号/依赖/签名
├── app/src/main/AndroidManifest.xml                       ← 单 Activity + ForegroundService
├── app/src/main/cpp/CMakeLists.txt                        ← .so 编译配置
├── app/src/main/cpp/llama_jni.cpp                         ← JNI bridge（477 行，核心！）
├── app/src/main/java/com/xuedi/coder/
│   ├── App.kt                                             ← application + 单例
│   ├── MainActivity.kt                                    ← 入口 + 权限
│   ├── model/
│   │   ├── LlmEngine.kt                                   ← ChatChunk + interface
│   │   ├── LlamaJniEngine.kt                              ← JNI 封装 + chatFlow + timeout + mutex防崩
│   │   ├── LlamaEngineHolder.kt                           ← 全局单例
│   │   ├── MockLlmEngine.kt                               ← fallback
│   │   ├── ModelManager.kt                                ← GGUF 文件管理
│   │   └── InferenceForegroundService.kt                  ← 后台推理保活
│   ├── vm/ChatViewModel.kt                                ← Mutex + StateFlow 状态管理
│   ├── ui/screen/
│   │   ├── ChatPage.kt                                    ← 聊天页（气泡/状态条/取消按钮）
│   │   ├── SettingsPage.kt                                ← 设置页（主题 Chip + 模型管理）
│   │   ├── PluginsPage.kt                                 ← 场景开关（TODO-3 15sp）
│   │   ├── AppNavHost.kt                                  ← BottomNav 路由
│   │   ├── AboutPage.kt                                   ← 应用信息页
│   │   └── BackgroundContainer.kt                         ← 照片背景遮罩
│   ├── ui/theme/{Theme.kt, Color.kt, Type.kt}             ← 主题（3模式自定义深色）
│   ├── theme/ThemeStore.kt                                ← ThemeMode + 背景 DataStore
│   ├── data/                                              ← Room 数据库（Chat/Model/Plugin）
│   └── plugin/{PluginManager.kt, PluginConfig.kt}         ← 场景插件 + system prompt 拼接
├── scripts/release-v1.3.0.sh                              ← 一键出包脚本
├── .github/workflows/build.yml                            ← GitHub Actions CI（构建 + 上传镜像链）
└── docs/交接报告-v1.3.0-脚本已验证.md                     ← 完整 SOP + 架构说明
```

---

## 七、用户可直接跑的验证命令（给 DeepSeek，若要复现）

```bash
# 克隆+签出当前版本
git clone https://github.com/Kdkdmwnwdkd/ai-coder.git
cd ai-coder
git checkout v1.3.2   # commit e8ec503

# 本地构建（需要 Android SDK + NDK 26.1.10909125 + CMake 3.22.1）
export ANDROID_HOME=~/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleRelease
```

---

## 八、给当前用户的临时解决方案（不改代码就能验证）

**按顺序试，每步发"你好"看能不能回答：**

1. **关所有后台 App**（微信、QQ、浏览器、淘宝等）+ **重启手机** → 回 APP 设置确认模型已加载 → 发"你好"
2. **场景页关 3 个开关**（只留 Android / Jetpack Compose 一个）→ 发"你好"
3. **场景页 4 个全关** → 发"你好"（这步应该 8 秒内能回答）
4. **设置页 → 模型 → 重新加载到内存** → 发"你好"

如果 ①②③④ 都闪退 = 确认不是 prompt 长度问题 → 是 **OOM / JNI SIGSEGV**，需要看 logcat 信号栈。

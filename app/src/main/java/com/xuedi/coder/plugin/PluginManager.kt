package com.xuedi.coder.plugin

import android.content.Context
import com.xuedi.coder.App
import com.xuedi.coder.data.PluginDao
import com.xuedi.coder.data.PluginDatabase
import com.xuedi.coder.data.PluginEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalSerializationApi::class)
class PluginManager(private val ctx: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val dao: PluginDao by lazy { PluginDatabase.get(ctx).dao() }
    private val pluginsDir: File by lazy { File(ctx.filesDir, "plugins").apply { mkdirs() } }
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 界面用：(配置, 是否启用) 的实时流 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val listFlow: Flow<List<Pair<PluginConfig, Boolean>>> = dao.observeAll().mapLatest { entities ->
        entities.mapNotNull { e ->
            loadFromDir(e.folderName)?.let { cfg -> cfg to e.enabled }
        }
    }

    suspend fun ensureBuiltinPlugins() {
        val builtins = listOf(
            "android_dev",
            "java_backend",
            "python_script",
            "shell_gradle"
        )
        builtins.forEach { name ->
            val target = File(pluginsDir, "$name/plugin.json")
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                runCatching {
                    ctx.assets.open("plugins/$name/plugin.json").use { ins ->
                        target.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                }
            }
        }
        // 扫一遍，没在Room里的upsert进去
        rescan()
    }

    suspend fun rescan() {
        _refreshing.value = true
        try {
            val folders = pluginsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            folders.forEach { folder ->
                val cfgFile = File(folder, "plugin.json")
                if (!cfgFile.exists()) return@forEach
                val cfg = runCatching { json.decodeFromString(PluginConfig.serializer(), cfgFile.readText()) }
                    .getOrNull() ?: return@forEach
                val existing = dao.getById(cfg.id)
                dao.upsert(
                    PluginEntity(
                        id = cfg.id,
                        name = cfg.name,
                        description = cfg.description,
                        version = cfg.version,
                        enabled = existing?.enabled ?: true,
                        folderName = folder.name
                    )
                )
            }
        } finally {
            _refreshing.value = false
        }
    }

    suspend fun toggle(id: String, enable: Boolean) {
        dao.setEnabled(id, enable)
    }

    suspend fun buildMergedSystemPrompt(): String {
        val base = StringBuilder(BASE_PROMPT)
        val enabled = dao.getAll().filter { it.enabled }
        enabled.forEach { entity ->
            loadFromDir(entity.folderName)?.let { cfg ->
                if (cfg.type == "system_prompt" && cfg.inject_system.isNotBlank()) {
                    base.append("\n\n### 场景：").append(cfg.name).append("\n")
                    base.append(cfg.inject_system.trim())
                }
            }
        }
        base.append("\n\n").append(ACTION_RULE)
        return base.toString()
    }

    // ---------- internal ----------
    private fun loadFromDir(folderName: String): PluginConfig? {
        val f = File(pluginsDir, "$folderName/plugin.json")
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(PluginConfig.serializer(), f.readText()) }.getOrNull()
    }

    private fun InputStream.copyTo(out: java.io.OutputStream) {
        val buf = ByteArray(8192)
        var n: Int
        while (read(buf).also { n = it } > 0) out.write(buf, 0, n)
    }

    companion object {
        const val BASE_PROMPT = """
你是运行在用户手机本地的「AI编程助手」。
- 全程使用简体中文回答，解释简洁到位。
- 写代码时给出可以直接复制的完整片段，保留全部 import 语句和必要上下文。
- 不编造不存在的 API；不确定就明确标注"需要验证"。
- 回答中的代码块必须用 ```语言``` 形式包裹，例如 ```kotlin ... ``` 或 ```bash ... ```。
        """.trimIndent()

        const val ACTION_RULE = """
如需帮用户执行操作（复制代码、打开APP、跳转设置等），请在代码或回答最后附带一个完整的 ACTION 标签：
 <ACTION: 动作名 参数>
可用动作（白名单）：
- copy_to_clipboard "要复制的文本或代码"
- open_app "com.xxx.package" 例如 open_app "com.android.settings" / open_app "com.tencent.mm"
- open_browser "https://..."
- show_toast "提示信息"
- vibrate_once
- take_screenshot
- set_brightness_low | set_brightness_high

ACTION 标签必须完整输出，前后各留一个空格。
        """.trimIndent()
    }
}

package com.xuedi.coder.plugin

import android.content.Context
import com.xuedi.coder.data.PluginDao
import com.xuedi.coder.data.PluginDatabase
import com.xuedi.coder.data.PluginEntity
import com.google.gson.Gson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import java.io.File
import java.io.InputStream

/**
 * 【新 M4 = 管理层】插件管理。
 *
 * 关键改造（避开 kotlinx.serialization-gradle-plugin 全网 404 的坑）：
 *   · 删除 @Serializable、删除 import kotlinx.serialization.json.Json
 *   · 两处 Json.decodeFromString(PluginConfig.serializer(), text)
 *     → 改为 Gson().fromJson(text, PluginConfig::class.java)
 *
 * Gson 是纯 runtime JAR（implementation("com.google.code.gson:gson:2.10.1")），
 * 不需要任何 Gradle 插件，MavenCentral 100% 能拉。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginManager(private val ctx: Context) {

    // Gson 实例：忽略未知字段（Json 默认也是这个策略，保持等价）
    private val gson = Gson()

    private val dao: PluginDao by lazy { PluginDatabase.get(ctx).dao() }
    private val pluginsDir: File by lazy { File(ctx.filesDir, "plugins").apply { mkdirs() } }
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** 界面用：(配置, 是否启用) 的实时流 */
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
                        target.outputStream().use { outs -> ins.copyToStream(outs) }
                    }
                }
            }
        }
        // 如果 assets 里没有插件文件（M4 阶段先硬编码一份兜底，避免 plugins 列表为空）
        ensureFallbackBuiltins()
        rescan()
    }

    /**
     * M4 临时兜底：assets/plugins/... 还没放时，直接在 filesDir/plugins/ 里写 4 个 plugin.json，
     * 这样 PluginsPage 立刻有内容可展示。等后期真正的插件资源包有了再走 assets。
     */
    private fun ensureFallbackBuiltins() {
        writeIfMissing("android_dev", PluginConfig(
            id = "android_dev",
            name = "Android 开发",
            description = "Jetpack Compose / Room / Coroutines / Service 等场景模板",
            type = "system_prompt",
            inject_system = """
你现在是 Android 专家。
- 优先 Kotlin + Jetpack Compose + Material3。
- 给出代码时，带上完整 import，使用 androidx 稳定版依赖。
- 权限相关：Android 13+ 需要 POST_NOTIFICATIONS/READ_MEDIA_IMAGES；存储访问走 SAF/FileProvider，不要用 MANAGE_EXTERNAL_STORAGE。
- 后台保活：前台服务 + startForeground，魅族/小米等 ROM 需要额外提示用户手动打开后台运行无限制。
            """.trimIndent()
        ))
        writeIfMissing("java_backend", PluginConfig(
            id = "java_backend",
            name = "Java 后端",
            description = "Spring Boot / Maven / Gradle / SQL",
            type = "system_prompt",
            inject_system = """
你现在是 Java 后端专家。
- 优先 Spring Boot 3.x + Java 17 + Maven。
- 给出 Controller / Service / Mapper / Entity 分层代码。
            """.trimIndent()
        ))
        writeIfMissing("python_script", PluginConfig(
            id = "python_script",
            name = "Python 脚本",
            description = "数据处理 / 小工具 / 爬虫 / 自动化",
            type = "system_prompt",
            inject_system = """
你现在是 Python 专家。
- 优先 Python 3.10+，使用 type hints，函数都加 docstring。
- 依赖在代码顶部注释写出 requirements（版本号范围）。
            """.trimIndent()
        ))
        writeIfMissing("shell_gradle", PluginConfig(
            id = "shell_gradle",
            name = "Shell / Gradle",
            description = "Bash 脚本 / Gradle 构建 / 打包签名 / GitHub Actions",
            type = "system_prompt",
            inject_system = """
你现在是构建&脚本专家。
- Shell 脚本使用 set -e 显式失败；避免有坑的 eval/cat。
- Gradle 优先 Kotlin DSL（*.kts），避免 Groovy DSL 陷阱。
            """.trimIndent()
        ))
    }

    private fun writeIfMissing(folder: String, cfg: PluginConfig) {
        val target = File(pluginsDir, "$folder/plugin.json")
        if (target.exists()) return
        target.parentFile?.mkdirs()
        target.writeText(gson.toJson(cfg))
    }

    suspend fun rescan() {
        _refreshing.value = true
        try {
            val folders = pluginsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            folders.forEach { folder ->
                val cfgFile = File(folder, "plugin.json")
                if (!cfgFile.exists()) return@forEach
                // ★ 这里是 serialization→Gson 的关键替换点 ★
                // 原：json.decodeFromString(PluginConfig.serializer(), cfgFile.readText())
                // 新：gson.fromJson(cfgFile.readText(), PluginConfig::class.java)
                val cfg = runCatching { gson.fromJson(cfgFile.readText(), PluginConfig::class.java) }
                    .getOrNull() ?: return@forEach
                val existing = dao.getById(cfg.id)
                dao.upsert(
                    PluginEntity(
                        id = cfg.id,
                        name = cfg.name,
                        description = cfg.description,
                        version = cfg.version,
                        // 🔴 OOM 修复：场景首次写入数据库时默认 false，让用户按需开启。
                        //    之前 true → 4 场景全开会拼 600-800 token system prompt，
                        //    手机 CPU 预填充 20-30s → 极易 OOM SIGSEGV 闪退。
                        enabled = existing?.enabled ?: false,
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
        base.append("\n\n").append(TOOL_USE_SECTION)
        return base.toString()
    }

    // ---------- internal ----------
    private fun loadFromDir(folderName: String): PluginConfig? {
        val f = File(pluginsDir, "$folderName/plugin.json")
        if (!f.exists()) return null
        // ★ 第二处 serialization→Gson 替换点 ★
        return runCatching { gson.fromJson(f.readText(), PluginConfig::class.java) }.getOrNull()
    }

    private fun InputStream.copyToStream(out: java.io.OutputStream) {
        val buf = ByteArray(8192)
        var n: Int
        while (read(buf).also { n = it } > 0) out.write(buf, 0, n)
    }

    companion object {
        // 注意：不能用 const val！因为 .trimIndent() 是运行时函数，
        // const val 要求编译时常量表达式（只能是字符串字面量/简单算术，不能调用任何方法）。
        // 去掉 const 即可，作为 companion object 的普通 val 使用完全等价（除了不能直接当 Java 注解参数——这里用不到）。
        val BASE_PROMPT = """
你是运行在用户手机本地的「AI编程助手」。
- 全程使用简体中文回答，解释简洁到位。
- 写代码时给出可以直接复制的完整片段，保留全部 import 语句和必要上下文。
- 不编造不存在的 API；不确定就明确标注"需要验证"。
- 回答中的代码块必须用 ```语言``` 形式包裹，例如 ```kotlin ... ``` 或 ```bash ... ```。
        """.trimIndent()

        val ACTION_RULE = """
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

        val TOOL_USE_SECTION = """
## 联网搜索（需要实时信息时必用）
用户发消息时如果开头写了 "@搜索 关键词"，系统会先自动去网上搜，把前 3 条搜索结果摘要拼在「【联网搜索结果】」段里，再把用户的原问题放在「【用户问题】」段里。
你看到上下文出现这两段时，回答必须**以搜索结果为事实依据**，不要再编造旧知识。不要在回答里提"联网搜索结果"这几个字，就像你本来就知道这些信息一样。

如果用户问题明显涉及：实时天气/日期、新闻、体育比分、股市行情、版本号、官网最新下载地址、最近事件、价格对比、政策变化 —— 这些必须依赖实时信息的场景，**建议你在回答里明确告诉用户："如果需要最新信息，请在问题前加上 @搜索 再提问，我会先联网搜索再回答"**。

## AI 执行模式（帮用户操作手机）
你可以直接在回答末尾输出 <ACTION: ...> 标签来帮用户操作手机，系统会自动执行，不需要用户再手动点开。
常用场景举例：
- 用户说"帮我复制这段代码" → 代码后跟 <ACTION: copy_to_clipboard "fun main() {...}">
- 用户说"打开设置" → <ACTION: open_app "com.android.settings">
- 用户说"帮我打开微信" → <ACTION: open_app "com.tencent.mm">
- 用户说"屏幕太暗了调亮" → <ACTION: set_brightness_high>
- 用户说"给我弹个提示" → <ACTION: show_toast "提示内容">
- 用户说"这个链接打开看看" → <ACTION: open_browser "https://...">

输出 <ACTION: ...> 标签的正确格式：
  标签必须用尖括号完整包裹，形如 <ACTION: open_app "com.tencent.mm">
  参数如果有空格或中文，必须用双引号包裹。
  每个标签单独占一行，写在回答的最后一行。
        """.trimIndent()
    }
}

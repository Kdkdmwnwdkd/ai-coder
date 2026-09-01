package com.xuedi.coder.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Copyright
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuedi.coder.BuildConfig
import com.xuedi.coder.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext

/**
 * 关于页（v1.2.5 正式版 UI）
 *
 * 原来写在 SettingsPage 里的所有长说明、排障步骤、模型下载地址、版本历史，
 * 全部集中到这里（正式应用的标准做法）。
 *
 * 内容分 5 张卡片：
 *   ① 应用信息（名称、版本、包名、架构、构建类型、开源地址）
 *   ② 使用指南 · 模型下载（推荐的 GGUF、下载链接、4 步上手流程）
 *   ③ 常见问题与排障（ctx=0 怎么办 / tokenize 失败怎么办 / 推理卡死怎么办 / 内存不足怎么办）
 *   ④ 版本更新记录（v1.0 起的 Changelog）
 *   ⑤ 开源组件与致谢（llama.cpp、AndroidX、Room、Coil、Material3、Jetpack Compose）
 */
@Composable
fun AboutPage() {
    val ctx = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- 卡片 1：应用信息 ----
        item {
            AboutSectionHeader(
                icon = { Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary) },
                title = stringResource(R.string.about_group_app)
            )
            OutlinedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    FormalRow(stringResource(R.string.about_app_name), stringResource(R.string.app_name))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    FormalRow(
                        stringResource(R.string.about_version),
                        "${BuildConfig.VERSION_NAME}（code ${BuildConfig.VERSION_CODE}）"
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    FormalRow(stringResource(R.string.about_build_type), BuildConfig.BUILD_TYPE)
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    FormalRow(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    FormalRow(stringResource(R.string.about_abi), "arm64-v8a（魅族20 / 骁龙 8 Gen2 等）")
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "GitHub 项目",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { openUrl("https://github.com/Kdkdmwnwdkd/ai-coder") }) {
                            Text(
                                "Kdkdmwnwdkd/ai-coder",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline
                            )
                        }
                    }
                }
            }
        }

        // ---- 卡片 2：使用指南 · 模型下载 ----
        item {
            AboutSectionHeader(
                icon = { Icon(Icons.Outlined.CloudDownload, null, tint = Color(0xFF1A8E4F)) },
                title = stringResource(R.string.about_group_guide)
            )
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "推荐模型（12GB RAM 机型最优平衡）",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Qwen2.5-3B-Instruct · Q4_K_M · GGUF · 约 2.1 GB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            openUrl("https://modelscope.cn/models/Qwen/Qwen2.5-3B-Instruct-GGUF")
                        }) {
                            Text(
                                "ModelScope 下载（国内更快）",
                                fontSize = 12.sp,
                                textDecoration = TextDecoration.Underline
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            openUrl("https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF")
                        }) {
                            Text(
                                "Hugging Face 镜像",
                                fontSize = 12.sp,
                                textDecoration = TextDecoration.Underline
                            )
                        }
                    }

                    Divider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Text(
                        "4 步开始本地推理",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    StepRow(1, stringResource(R.string.about_guide_1))
                    StepRow(2, stringResource(R.string.about_guide_2))
                    StepRow(3, stringResource(R.string.about_guide_3))
                    StepRow(4, stringResource(R.string.about_guide_4))
                }
            }
        }

        // ---- 卡片 3：常见问题与排障 ----
        item {
            AboutSectionHeader(
                icon = { Icon(Icons.Outlined.BugReport, null, tint = Color(0xFFC24141)) },
                title = stringResource(R.string.about_group_faq)
            )
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                val items = remember { FAQ_ITEMS }
                // 让每条 FAQ 可展开/收起（默认展开第一条）
                var openIdx by remember { mutableStateOf(0) }
                Column {
                    items.forEachIndexed { i, (q, a) ->
                        val isOpen = openIdx == i
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            TextButton(
                                onClick = { openIdx = if (isOpen) -1 else i },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isOpen) "▾ $q" else "▸ $q",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                    lineHeight = 17.sp
                                )
                            }
                            if (isOpen) {
                                Text(
                                    text = a,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, bottom = 4.dp)
                                )
                            }
                        }
                        if (i < items.size - 1) {
                            Divider(
                                modifier = Modifier.padding(start = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }

        // ---- 卡片 4：版本更新记录 ----
        item {
            AboutSectionHeader(
                icon = { Icon(Icons.Outlined.Update, null, tint = Color(0xFF1565C0)) },
                title = stringResource(R.string.about_group_changelog)
            )
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                val items = remember { VERSION_HISTORY }
                Column {
                    items.forEachIndexed { i, (ver, date, desc) ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "v$ver",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (i == 0) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                )
                                if (i == 0) {
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Text(
                                            "最新",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    date,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                desc,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                        if (i < items.size - 1) {
                            Divider(
                                modifier = Modifier.padding(start = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }

        // ---- 卡片 5：开源组件与致谢 ----
        item {
            AboutSectionHeader(
                icon = { Icon(Icons.Outlined.Copyright, null, tint = Color(0xFF6A1B9A)) },
                title = stringResource(R.string.about_group_license)
            )
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    val items = remember { LICENSE_ITEMS }
                    items.forEachIndexed { i, (name, url, lic, remark) ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Article, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                if (remark != null) {
                                    Text(
                                        remark,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "许可证：$lic",
                                    fontSize = 11.sp,
                                    color = Color(0xFF558B2F)
                                )
                            }
                            TextButton(onClick = { openUrl(url) }) {
                                Text(
                                    "主页",
                                    fontSize = 11.sp,
                                    textDecoration = TextDecoration.Underline
                                )
                            }
                        }
                        if (i < items.size - 1) {
                            Divider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "© AI 编程助手 · 所有推理均在您本地手机上完成，不收集任何数据。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

// ===================================================================
// 小辅助组件
// ===================================================================
@Composable
private fun AboutSectionHeader(
    icon: @Composable () -> Unit,
    title: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, bottom = 6.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun FormalRow(label: String, value: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StepRow(idx: Int, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Card(
            shape = RoundedCornerShape(7.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        ) {
            Text(
                " $idx ",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 8.dp, end = 2.dp)
        )
    }
}

// ===================================================================
// FAQ / 版本历史 / 开源致谢 的静态数据
// ===================================================================
private val FAQ_ITEMS: List<Pair<String, String>> = listOf(
    "Q1：设置里显示「已设为当前」但聊天页报「ctx=0 模型未加载」？" to
        "答：这是 Room 标记了 selected=true，但模型没能真正加载进内存（比如预热失败）。" +
        "请打开「设置」→ 找到该模型 → 点「🔄 重新加载到内存」按钮，查看 Toast 的具体原因。" +
        "常见原因：① 手机剩余内存不足（建议关闭所有后台 App 或重启手机后重试）；" +
        "② GGUF 文件损坏；③ JNI libxuedi-llama.so 未加载（请确认安装包是 arm64-v8a）。",

    "Q2：聊天页出现「llama_tokenize 失败 返回 -1214」？" to
        "答：这是 v1.2.4 之前的 bug—— llama.cpp 的 tokenize 估算 buffer 时，" +
        "返回负值表示「实际需要 |ret| 个 token」，不是错误。v1.2.4+ 已修复（取 abs(need) 分配 buffer）。" +
        "如仍遇到，请升级到最新 APK 再试。",

    "Q3：点「加载到内存」后 Toast 提示「内存不足 / ctx=0」？" to
        "答：3B 模型 Q4_K_M 大约需要 3.5~4.5 GB 可用内存（KV Cache + 权重页）。请按顺序尝试：" +
        "① 关闭所有后台 App（微信、QQ、浏览器、视频等）；② 重启手机 → 开机后先开本 App，" +
        "不要先开其他 App；③ 仍不行就换成更小模型（如 Qwen2.5-1.5B-Instruct Q4_K_M，约 1.1GB）。",

    "Q4：开始推理后打字很慢？" to
        "答：手机 CPU 本地跑 3B 模型，纯 CPU 解码速度约 4~8 tokens/秒（魅族 20 骁龙 8 Gen2）。" +
        "首次推理（prefill 阶段）需要几秒钟处理你的输入后才会开始出字，请耐心等待。" +
        "长时间推理期间，通知栏会有「本地推理保活通道」前台服务，防止 Flyme 杀进程。",

    "Q5：推理中途 App 被杀或崩溃？" to
        "答：通常是系统 OOM killer 回收了进程。可尝试：① 给 App 开启「自启动」" +
        "与「后台无限制」授权（魅族 20：手机管家 → 权限管理 → 后台管理）；" +
        "② 替换成 1.5B 级别的小模型（约省一半内存）。",

    "Q6：模型下载后导入提示「GGUF 校验失败」？" to
        "答：通常是下载中途被中断导致文件截断。请重新下载 GGUF，推荐用下载工具（IDM/ADM）" +
        "或 torrent 方式，避免浏览器单线程下载断连。下载完成后在文件管理器里核对文件大小。",

    "Q7：如何给我们提 Bug 或建议？" to
        "答：直接在 GitHub 项目 https://github.com/Kdkdmwnwdkd/ai-coder/issues 提 Issue，" +
        "请附上手机型号、RAM 大小、APK 版本号（本页顶部可看）、错误截图或错误 Toast 文案。"
)

private val VERSION_HISTORY: List<Triple<String, String, String>> = listOf(
    Triple(
        "1.2.5 (2.1.2)", "2026-09-01",
        "UI 正式化：设置页去大段说明，改为外观/模型两张分组卡片；每个模型行新增「🔄 重新加载到内存」按钮与" +
            "内存状态条（已加载 ctx=… / 未加载 / 失败原因）；启动预热走 switchAndLoadModel 并 Toast 结果；" +
            "关于页全新设计，含应用信息、模型下载指南与下载链接、FAQ 排障、版本历史、开源致谢。"
    ),
    Triple(
        "1.2.4 (2.1.1)", "2026-09-01",
        "C++ 层核心修复：llama_tokenize(buffer=nullptr) 的返回值为负时，表示" +
            "「需要的 token 个数 = abs(need)」不是错误，修复后按 abs(need)+8 分配 buffer，" +
            "解决 Qwen2.5 系列中文 prompt 报 -1214 错误的问题。"
    ),
    Triple(
        "1.2.3 (2.1.0-diagnose)", "2026-09-01",
        "诊断防线升级：模型加载失败时 ctx=0 不再静默 fallback Mock，聊天页直接返回 Error 并附排障步骤；" +
            "SettingsPage Toast 提示升级为完整诊断（ctx 值、文件状态、错误原因、4 条处理建议）。"
    ),
    Triple(
        "1.2.2", "2026-08-31",
        "多话题框架 ANR 修复：发送消息触发 touchActive 时不再重新加载 DB 消息覆盖 in-memory 流式回复，" +
            "彻底修复 v1.2.1 中「发消息卡死」的问题。"
    ),
    Triple(
        "1.2.0 / 1.2.1", "2026-08-31",
        "新增多话题侧边栏（Drawer 对话列表 + 新建/重命名/删除）；ModelManager 增加 switchAndLoadModel " +
            "统一入口，先 release 旧模型再 load，默认 nCtx=2048 降低内存占用。"
    ),
    Triple(
        "1.1.0", "2026-08-30",
        "M5 JNI 接入：集成 llama.cpp（b4812→b4835）子模块 + CMake arm64-v8a 交叉编译 + 前台推理保活服务；" +
            "GGUF SAF 导入（复制到 filesDir/models + Room + GGUF 魔数校验）；真流式推理替代 Mock。"
    ),
    Triple(
        "1.0.0", "2026-08-29",
        "首个可用版本：M3 四层 UI（对话/场景/设置/关于 底部导航）+ 主题背景设置 + 聊天历史 Room 持久化 + Mock 推理引擎。"
    )
)

private data class LicenseItem(
    val name: String,
    val url: String,
    val license: String,
    val note: String?
)

private val LICENSE_ITEMS: List<LicenseItem> = listOf(
    LicenseItem(
        "llama.cpp", "https://github.com/ggerganov/llama.cpp", "MIT",
        "本地 GGUF 推理核心（CPU + llama.cpp C++ backend）。致敬 ggerganov 等贡献者。"
    ),
    LicenseItem(
        "AndroidX Room", "https://developer.android.com/jetpack/androidx/releases/room", "Apache-2.0",
        "SQLite 持久化层：模型/消息/话题三张表。"
    ),
    LicenseItem(
        "Jetpack Compose + Material3", "https://developer.android.com/jetpack/compose", "Apache-2.0",
        "UI 界面框架（底部 Tab / 卡片 / Drawer / 流式布局）。"
    ),
    LicenseItem(
        "Coil", "https://coil-kt.github.io/coil/", "Apache-2.0",
        "背景照片与图像异步加载（Kotlin Coroutine first）。"
    ),
    LicenseItem(
        "Kotlin Coroutines & Flow", "https://github.com/Kotlin/kotlinx.coroutines", "Apache-2.0",
        "异步调度、StateFlow 响应式 UI、Room Flow 监听。"
    ),
    LicenseItem(
        "DataStore Preferences", "https://developer.android.com/topic/libraries/architecture/datastore", "Apache-2.0",
        "主题/背景/透明度 键值持久化（ThemeStore）。"
    ),
    LicenseItem(
        "Koin", "https://insert-koin.io/", "Apache-2.0",
        "轻量级依赖注入（ViewModel / EngineHolder / DAO 管理）。"
    ),
    LicenseItem(
        "Qwen 系列模型", "https://modelscope.cn/models/Qwen/", "Apache-2.0",
        "阿里云千问开源模型，Qwen2.5-3B-Instruct 的 GGUF 量化版本为本应用推荐默认模型。"
    )
)

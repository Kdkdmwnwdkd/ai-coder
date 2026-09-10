package com.novelseek.ultra.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelseek.ultra.ui.theme.Accent
import com.novelseek.ultra.ui.theme.OnBackground
import com.novelseek.ultra.ui.viewmodel.AgentConfigViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentConfigScreen(
    workId: Long,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: AgentConfigViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AgentConfigViewModel(
                    context.applicationContext as Application,
                    workId
                ) as T
            }
        }
    )

    val config by viewModel.config.collectAsState()

    var writingStyle by remember { mutableStateOf(config?.writingStyle ?: "") }
    var continuePrompt by remember { mutableStateOf(config?.continuePrompt ?: "") }
    var polishPrompt by remember { mutableStateOf(config?.polishPrompt ?: "") }
    var expandPrompt by remember { mutableStateOf(config?.expandPrompt ?: "") }
    var ideaPrompt by remember { mutableStateOf(config?.ideaPrompt ?: "") }
    var selectedPreset by remember { mutableStateOf(0) }

    LaunchedEffect(config) {
        config?.let {
            writingStyle = it.writingStyle
            continuePrompt = it.continuePrompt
            polishPrompt = it.polishPrompt
            expandPrompt = it.expandPrompt
            ideaPrompt = it.ideaPrompt
        }
    }

    val presets = remember {
        listOf(
            "无预设" to WritingStylePreset.NONE,
            "都市言情" to WritingStylePreset.URBAN_ROMANCE,
            "玄幻修仙" to WritingStylePreset.XIANXIA,
            "悬疑推理" to WritingStylePreset.SUSPENSE,
            "历史架空" to WritingStylePreset.HISTORY,
            "科幻未来" to WritingStylePreset.SCI_FI,
            "轻小说" to WritingStylePreset.LIGHT_NOVEL,
            "严肃文学" to WritingStylePreset.LITERARY
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent 配置", color = OnBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = OnBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = {
                        viewModel.updateConfig(
                            writingStyle = writingStyle,
                            continuePrompt = continuePrompt,
                            polishPrompt = polishPrompt,
                            expandPrompt = expandPrompt,
                            ideaPrompt = ideaPrompt
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("保存配置")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "写作风格预设（去 AI 味）",
                style = MaterialTheme.typography.titleMedium,
                color = OnBackground,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )

            Text(
                "选择预设后，会自动填充写作风格和提示词，帮助 AI 生成更有人味、更少模板感的文字。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEachIndexed { index, (label, preset) ->
                    FilterChip(
                        selected = selectedPreset == index,
                        onClick = {
                            selectedPreset = index
                            if (preset != WritingStylePreset.NONE) {
                                writingStyle = preset.styleDescription
                                continuePrompt = preset.continuePrompt
                                polishPrompt = preset.polishPrompt
                                expandPrompt = preset.expandPrompt
                                ideaPrompt = preset.ideaPrompt
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                "自定义写作风格",
                style = MaterialTheme.typography.titleMedium,
                color = OnBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = writingStyle,
                onValueChange = { writingStyle = it },
                label = { Text("写作风格描述") },
                placeholder = { Text("例如：冷峻克制的白描风格，多用短句，不堆砌辞藻...") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "各功能提示词",
                style = MaterialTheme.typography.titleMedium,
                color = OnBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = continuePrompt,
                onValueChange = { continuePrompt = it },
                label = { Text("续写提示词") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = polishPrompt,
                onValueChange = { polishPrompt = it },
                label = { Text("润色提示词") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = expandPrompt,
                onValueChange = { expandPrompt = it },
                label = { Text("扩写提示词") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = ideaPrompt,
                onValueChange = { ideaPrompt = it },
                label = { Text("灵感提示词") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

enum class WritingStylePreset(
    val styleDescription: String,
    val continuePrompt: String,
    val polishPrompt: String,
    val expandPrompt: String,
    val ideaPrompt: String
) {
    NONE("", "", "", "", ""),

    URBAN_ROMANCE(
        styleDescription = "细腻温婉的都市言情风格。多用感官描写（视觉、听觉、触觉），注重人物内心独白和情绪流动。对话自然口语化，避免过于书面化。场景切换平滑，节奏舒缓。",
        continuePrompt = "请根据前文继续创作一段都市言情内容。注意：1）多用感官描写，让读者能\u201c看见\u201d场景；2）人物对话要自然口语化，像真实生活中那样；3）适当加入内心独白，展现人物情感变化；4）不要堆砌华丽辞藻，保持简洁细腻；5）情节推进要有生活气息，避免狗血套路。",
        polishPrompt = "请润色以下文字，使其更符合都市言情的细腻风格。要求：1）增加感官细节描写；2）优化对话，让它更自然口语化；3）适当加入人物内心活动；4）去除生硬的过渡句和模板化表达；5）保持语言简洁，不要过度修饰。",
        expandPrompt = "请扩写以下内容，增加细节描写。重点增加：1）环境氛围描写（光线、气味、声音）；2）人物的表情和微动作；3）内心独白和心理活动；4）对话中的潜台词和停顿。注意不要过度堆砌形容词，保持克制和真实感。",
        ideaPrompt = "请基于以下背景信息，提供几个都市言情类的创意灵感。要求：1）情节设计要贴近现实生活，有代入感；2）冲突要自然合理，不要为虐而虐；3）人物关系发展要有层次，不要一见钟情就万事大吉；4）建议要具体可操作，方便直接展开创作。"
    ),

    XIANXIA(
        styleDescription = "古典大气的玄幻修仙风格。文笔苍劲有力，善用意象和典故。场景描写有层次感（远景—中景—近景），动作戏干脆利落。人物对话带有古风韵味但不过度文言。世界观设定严谨，前后一致。",
        continuePrompt = "请续写一段玄幻修仙内容。要求：1）保持古典大气的文风，善用意象；2）动作描写要干脆利落，有画面感；3）场景描写有层次，先远景再近景；4）对话带古风韵味但 readable；5）不要堆砌\u201c逆天\u201d\u201c绝世\u201d等浮夸词汇；6）力量体系要自洽，不要突然越级。",
        polishPrompt = "请润色这段玄幻修仙文字。要求：1）优化场景描写的层次感；2）精简动作描写，去掉冗余副词；3）对话适当加入古风韵味但保持可读；4）去除模板化的\u201c震惊\u201d\u201c瞳孔收缩\u201d等套路描写；5）增强画面感和沉浸感。",
        expandPrompt = "请扩写这段玄幻内容，重点增加：1）修炼体系的细节描写（灵气流转、功法运行）；2）战斗场景的空间感和节奏感；3）法宝/功法的独特设定描述；4）环境氛围（天象、地势、灵气浓度）。注意保持设定严谨，前后一致。",
        ideaPrompt = "请提供玄幻修仙类的创意灵感。要求：1）修炼体系要有创新点，不要照搬常见套路；2）世界观设定要有独特之处；3）人物动机要合理，不要为了升级而升级；4）情节设计要有张有弛，不要一味打怪升级。"
    ),

    SUSPENSE(
        styleDescription = "冷峻克制的悬疑推理风格。多用白描，少用形容词。信息逐步释放，制造悬念感。心理描写精准，不夸张。节奏紧凑，章节末尾留钩子。逻辑严密，推理过程清晰。",
        continuePrompt = "请续写悬疑推理内容。要求：1）保持冷峻克制的笔调，多用白描；2）信息要逐步释放，每段至少埋一个悬念点；3）心理描写精准克制，不要过度渲染；4）节奏紧凑，场景切换要快；5）推理过程要有逻辑链条，不要突然顿悟。",
        polishPrompt = "请润色这段悬疑文字。要求：1）精简冗余描写，多用白描；2）调整信息释放的节奏，让悬念更自然；3）去除过度的心理渲染；4）优化推理逻辑链条；5）在段落结尾增加悬念钩子。",
        expandPrompt = "请扩写这段悬疑内容，重点增加：1）环境氛围的诡异感（光线、声音、气味）；2）人物细微的表情和动作暗示；3）线索的隐藏和暗示；4）时间/空间细节的铺垫。注意保持信息逐步释放的节奏，不要一次暴露太多。",
        ideaPrompt = "请提供悬疑推理类的创意灵感。要求：1）诡计设计要新颖，不要照搬经典套路；2）动机要深层合理，不是简单的爱恨情仇；3）线索布置要有层次，读者能参与推理；4）反转要有铺垫，不要突兀。"
    ),

    HISTORY(
        styleDescription = "厚重沉稳的历史架空风格。注重时代细节的还原，人物语言符合时代背景。大场面有史诗感，小场面有人情味。政治斗争和心理博弈并重。文笔凝练，不注水。",
        continuePrompt = "请续写历史架空内容。要求：1）注重时代细节，服装、礼仪、建筑要有考据感；2）人物语言符合身份和时代背景；3）大场面要有史诗感，小场面有人情味；4）政治博弈要写清利益链条；5）不要过度美化或丑化历史人物原型。",
        polishPrompt = "请润色这段历史架空文字。要求：1）检查时代细节的一致性；2）优化人物对话，使其更符合身份背景；3）精简冗余场景描写；4）增强权力博弈的层次感；5）去除现代口语化的表达。",
        expandPrompt = "请扩写这段历史内容，重点增加：1）时代细节的描写（服饰、器物、建筑）；2）权力博弈的心理活动；3）历史事件的蝴蝶效应铺垫；4）人物关系的复杂性描写。注意保持历史感和真实感。",
        ideaPrompt = "请提供历史架空类的创意灵感。要求：1）切入点要新颖，不要只写帝王将相；2）时代变革要有内在逻辑；3）人物选择要受时代局限，不要开天眼；4）建议要结合具体历史事件或制度。"
    ),

    SCI_FI(
        styleDescription = "硬核又不失人文关怀的科幻风格。科技设定有科学依据，不凭空想象。探讨科技与人性的关系。叙事视角独特，可以是 AI、非人类视角。语言精准，技术术语使用得当。",
        continuePrompt = "请续写科幻内容。要求：1）科技设定要有科学依据或合理推演；2）探讨科技与人性的关系，不要只写技术奇观；3）叙事视角可以有新意；4）技术术语使用准确，不要瞎编；5）情感表达要克制，用细节打动人。",
        polishPrompt = "请润色这段科幻文字。要求：1）检查科技设定的自洽性；2）优化技术术语的使用；3）增强科技与人性冲突的深度；4）精简不必要的概念解释；5）保留独特的叙事视角。",
        expandPrompt = "请扩写这段科幻内容，重点增加：1）科技细节的可视化描写；2）未来社会的结构性描写；3）科技对人际关系的影响；4）环境变化的细节暗示。注意保持科学严谨性。",
        ideaPrompt = "请提供科幻类的创意灵感。要求：1）科技设定要有创新且可推演；2）核心冲突要围绕科技与人性的关系；3）世界观要有系统性设计；4）建议要有哲学深度，不只是技术升级。"
    ),

    LIGHT_NOVEL(
        styleDescription = "轻松活泼的轻小说风格。第一人称或近第三人称视角，吐槽和内心 OS 丰富。节奏轻快，每章有明确的情绪起伏。人物性格鲜明，对话有萌点或槽点。适度的二次元梗但不过分。",
        continuePrompt = "请续写轻小说风格的内容。要求：1）保持轻松活泼的节奏；2）增加人物内心吐槽和 OS；3）对话要有鲜明的人物性格；4）适度玩梗但不要过度；5）每段要有情绪起伏，避免平淡；6）场景转换要有趣。",
        polishPrompt = "请润色这段轻小说文字。要求：1）增加人物的内心吐槽；2）优化对话，让性格更鲜明；3）调整节奏，增加情绪起伏；4）去除过于严肃的表达；5）增加适当的萌点或槽点。",
        expandPrompt = "请扩写这段轻小说内容，重点增加：1）主角的内心吐槽和反应；2）人物之间的化学反应；3）日常细节的趣味性；4）场景的轻松氛围描写。注意保持轻小说的节奏感。",
        ideaPrompt = "请提供轻小说类的创意灵感。要求：1）设定要有趣但不复杂；2）人物性格要鲜明有反差萌；3）日常和主线要有机结合；4）建议要有笑点或萌点，不要全程严肃。"
    ),

    LITERARY(
        styleDescription = "深沉内敛的严肃文学风格。关注人性和存在的主题。象征和隐喻丰富。叙事时间可以非线性。语言凝练，每个词都有分量。情感表达含蓄，靠细节和留白打动人。",
        continuePrompt = "请续写严肃文学风格的内容。要求：1）保持深沉内敛的笔调；2）关注人性和存在的深层主题；3）善用象征和隐喻；4）情感表达要含蓄，靠细节打动人；5）语言凝练，不要冗余；6）可适当使用非线性叙事。",
        polishPrompt = "请润色这段文学性文字。要求：1）精简语言，让每个词都有分量；2）增强象征和隐喻层次；3）情感表达要更含蓄；4）增加留白，给读者想象空间；5）去除直白的解释和说教。",
        expandPrompt = "请扩写这段文学内容，重点增加：1）环境和物象的象征意义；2）人物内心活动的深层描写；3）时间和记忆的流动感；4）留白的艺术处理。注意保持凝练，不要过度铺陈。",
        ideaPrompt = "请提供严肃文学类的创意灵感。要求：1）主题要围绕人性或存在的困境；2）人物要有复杂性和矛盾性；3）情节要服务于主题表达；4）建议要有文学性和思想深度。"
    )
}

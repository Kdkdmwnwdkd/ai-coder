package com.novelseek.ultra.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novelseek.ultra.data.local.AppDatabase
import com.novelseek.ultra.data.local.PreferencesDataStore
import com.novelseek.ultra.data.model.*
import com.novelseek.ultra.data.repository.*
import com.novelseek.ultra.service.AiService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChapterEditViewModel(
    application: Application,
    private val chapterId: Long,
    private val workId: Long
) : AndroidViewModel(application) {

    private val chapterRepository: ChapterRepository
    private val workRepository: WorkRepository
    private val agentConfigRepository: AgentConfigRepository
    private val outlineRepository: OutlineRepository
    private val characterRepository: CharacterRepository
    private val worldRepository: WorldSettingRepository
    private val preferencesDataStore = PreferencesDataStore(application)
    private val aiService = AiService()

    private val _chapter = MutableStateFlow<Chapter?>(null)
    val chapter: StateFlow<Chapter?> = _chapter

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading

    private val _aiResult = MutableStateFlow<String?>(null)
    val aiResult: StateFlow<String?> = _aiResult

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        val db = AppDatabase.getDatabase(application)
        chapterRepository = ChapterRepository(db.chapterDao())
        workRepository = WorkRepository(db.workDao())
        agentConfigRepository = AgentConfigRepository(db.agentConfigDao())
        outlineRepository = OutlineRepository(db.outlineDao())
        characterRepository = CharacterRepository(db.characterDao())
        worldRepository = WorldSettingRepository(db.worldSettingDao())

        viewModelScope.launch {
            _chapter.value = chapterRepository.getById(chapterId)
        }
    }

    fun updateContent(content: String) {
        viewModelScope.launch {
            _chapter.value?.let { ch ->
                val updated = ch.copy(content = content, updatedAt = System.currentTimeMillis())
                chapterRepository.update(updated)
                _chapter.value = updated
                workRepository.updateTime(workId)
            }
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            _chapter.value?.let { ch ->
                val updated = ch.copy(title = title, updatedAt = System.currentTimeMillis())
                chapterRepository.update(updated)
                _chapter.value = updated
            }
        }
    }

    fun aiContinue() {
        performAiAction("续写") { config, content ->
            val context = buildContext()
            "${config.continuePrompt}\n\n作品背景：\n$context\n\n前文内容：\n$content\n\n请继续创作："
        }
    }

    fun aiPolish() {
        performAiAction("润色") { config, content ->
            "${config.polishPrompt}\n\n待润色内容：\n$content"
        }
    }

    fun aiExpand() {
        performAiAction("扩写") { config, content ->
            "${config.expandPrompt}\n\n待扩写内容：\n$content"
        }
    }

    fun aiIdea() {
        performAiAction("灵感") { config, content ->
            val context = buildContext()
            "${config.ideaPrompt}\n\n作品背景：\n$context\n\n当前内容：\n$content"
        }
    }

    private fun performAiAction(label: String, promptBuilder: suspend (AgentConfig, String) -> String) {
        viewModelScope.launch {
            _isAiLoading.value = true
            _aiResult.value = null
            _error.value = null

            try {
                val settings = preferencesDataStore.userSettings.first()
                val config = agentConfigRepository.getOrCreate(workId)
                val content = _chapter.value?.content ?: ""

                val prompt = promptBuilder(config, content)
                val systemPrompt = if (config.writingStyle.isNotBlank()) {
                    "你是一位专业的小说创作助手。写作风格要求：${config.writingStyle}"
                } else {
                    "你是一位专业的小说创作助手，擅长续写、润色、扩写和提供创意灵感。"
                }

                aiService.generate(settings, prompt, systemPrompt)
                    .onSuccess { result ->
                        _aiResult.value = result
                    }
                    .onFailure { e ->
                        _error.value = "AI $label 失败: ${e.message}"
                    }
            } catch (e: Exception) {
                _error.value = "AI $label 失败: ${e.message}"
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    private suspend fun buildContext(): String {
        val outline = outlineRepository.getByWorkId(workId)
        val characters = characterRepository.getByWorkId(workId).first()
        val worldSettings = worldRepository.getByWorkId(workId).first()

        val sb = StringBuilder()
        outline?.let { sb.appendLine("大纲：${it.content}") }
        if (characters.isNotEmpty()) {
            sb.appendLine("角色：")
            characters.forEach { sb.appendLine("- ${it.name}：${it.description}（${it.role}）") }
        }
        if (worldSettings.isNotEmpty()) {
            sb.appendLine("世界观：")
            worldSettings.forEach { sb.appendLine("- ${it.name}：${it.content}") }
        }
        return sb.toString().ifBlank { "暂无详细设定" }
    }

    fun applyAiResult() {
        _aiResult.value?.let { result ->
            val cleanResult = result.replace(Regex("【演示模式】.*?(?=\n\n|$$)", RegexOption.DOT_MATCHES_ALL), "").trim()
            updateContent(cleanResult)
            _aiResult.value = null
        }
    }

    fun clearAiResult() {
        _aiResult.value = null
    }

    fun clearError() {
        _error.value = null
    }
}

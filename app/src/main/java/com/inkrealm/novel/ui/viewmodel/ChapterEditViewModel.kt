package com.inkrealm.novel.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.inkrealm.novel.data.local.AppDatabase
import com.inkrealm.novel.data.local.PreferencesDataStore
import com.inkrealm.novel.data.model.*
import com.inkrealm.novel.data.repository.*
import com.inkrealm.novel.service.AiService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChapterEditViewModel(
    application: Application,
    private val chapterId: Long,
    private val workId: Long
) : AndroidViewModel(application) {

    private val chapterRepo: ChapterRepository
    private val workRepo: WorkRepository
    private val prefs = PreferencesDataStore(application)
    private val aiService = AiService()

    private val _chapter = MutableStateFlow<Chapter?>(null)
    val chapter: StateFlow<Chapter?> = _chapter

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading

    private val _aiResult = MutableStateFlow<String?>(null)
    val aiResult: StateFlow<String?> = _aiResult

    init {
        val db = AppDatabase.getDatabase(application)
        chapterRepo = ChapterRepository(db.chapterDao())
        workRepo = WorkRepository(db.workDao())
        loadChapter()
    }

    private fun loadChapter() {
        viewModelScope.launch {
            try { _chapter.value = chapterRepo.getById(chapterId) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun updateContent(content: String) {
        viewModelScope.launch {
            _chapter.value?.let { ch ->
                try {
                    val updated = ch.copy(content = content, wordCount = content.length, updatedAt = System.currentTimeMillis())
                    chapterRepo.update(updated)
                    _chapter.value = updated
                    workRepo.updateTime(workId)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    fun aiContinue() { performAi("续写") { config, content ->
        "请根据前文继续创作。要求保持原有风格和人物设定，续写一段自然流畅的内容。\n\n前文：\n$content" }
    }

    fun aiPolish() { performAi("润色") { _, content ->
        "请润色以下文字，使其更加生动流畅，保持原有情节和风格不变。\n\n$content" }
    }

    fun aiExpand() { performAi("扩写") { _, content ->
        "请扩写以下内容，增加细节描写、环境氛围和人物心理活动。\n\n$content" }
    }

    fun aiIdea() { performAi("灵感") { _, content ->
        "请根据以下背景提供几个创意情节发展建议。\n\n当前内容：\n$content" }
    }

    private fun performAi(label: String, promptBuilder: suspend (UserSettings, String) -> String) {
        viewModelScope.launch {
            _isAiLoading.value = true
            _aiResult.value = null
            try {
                val settings = prefs.userSettings.first()
                val content = _chapter.value?.content ?: ""
                val prompt = promptBuilder(settings, content)
                val systemPrompt = "你是一位专业的小说创作助手。"
                aiService.generate(settings, prompt, systemPrompt)
                    .onSuccess { result -> _aiResult.value = result }
                    .onFailure { e -> _aiResult.value = "【错误】${e.message}" }
            } catch (e: Exception) {
                _aiResult.value = "【错误】${e.message}"
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    fun applyAiResult() {
        _aiResult.value?.let { result ->
            val clean = result.replace(Regex("【演示模式】.*?(?=\n\n|$)", RegexOption.DOT_MATCHES_ALL), "").trim()
            if (clean.isNotBlank()) updateContent(clean)
            _aiResult.value = null
        }
    }

    fun clearAiResult() { _aiResult.value = null }
}

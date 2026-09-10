package com.novelseek.ultra.service

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.Context
import java.util.*

class TtsService(context: Context) {
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingText: String? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.US)
                    }
                    isReady = true
                    pendingText?.let { text ->
                        speak(text)
                        pendingText = null
                    }
                }
            }
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isReady) {
            pendingText = text
            return
        }
        tts?.let { engine ->
            if (engine.isSpeaking) {
                engine.stop()
            }
            val utteranceId = "novelseek_${System.currentTimeMillis()}"
            if (onComplete != null) {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onComplete()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    val isSpeaking: Boolean get() = tts?.isSpeaking ?: false
}

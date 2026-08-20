package com.theopadilha.falaagenda.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, UNDERSTANDING, ERROR }

data class VoiceUiState(
    val state: VoiceState = VoiceState.IDLE,
    val partial: String = "",
    val finalText: String? = null,
    val error: String? = null,
)

class VoiceCaptureController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!available()) {
            _ui.value = VoiceUiState(state = VoiceState.ERROR, error = "unavailable")
            return
        }
        stopInternal()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        _ui.value = VoiceUiState(state = VoiceState.LISTENING)
        sr.startListening(intent)
    }

    fun cancel() {
        stopInternal()
        _ui.value = VoiceUiState()
    }

    fun consumeFinal() {
        _ui.value = VoiceUiState()
    }

    private fun stopInternal() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.setRecognitionListener(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _ui.value = _ui.value.copy(state = VoiceState.LISTENING, error = null)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                _ui.value = _ui.value.copy(state = VoiceState.LISTENING, partial = text)
            }
        }

        override fun onEndOfSpeech() {
            _ui.value = _ui.value.copy(state = VoiceState.UNDERSTANDING)
        }

        override fun onError(error: Int) {
            val human = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> "Não consegui ouvir. Toque de novo e fale perto do microfone."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                -> "Preciso da permissão do microfone para ouvir você."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> "A fala precisa de um reconhecimento do aparelho. Tente de novo ou escreva o recado."
                else -> "Não consegui ouvir. Toque de novo ou escreva o recado."
            }
            _ui.value = VoiceUiState(state = VoiceState.ERROR, error = human)
            stopInternal()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            _ui.value = VoiceUiState(
                state = VoiceState.IDLE,
                finalText = text.ifBlank { null },
                error = if (text.isBlank()) "Não entendi o que foi dito." else null,
            )
            stopInternal()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

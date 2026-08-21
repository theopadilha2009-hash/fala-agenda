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

enum class VoiceState { IDLE, PREPARING, LISTENING, UNDERSTANDING, ERROR }

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

    private var session = false
    private var retries = 0

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!available()) {
            _ui.value = VoiceUiState(
                state = VoiceState.ERROR,
                error = "A fala não está disponível neste aparelho. Use o botão Escrever tarefa.",
            )
            return
        }
        session = true
        retries = 0
        _ui.value = VoiceUiState(state = VoiceState.PREPARING)
        beginListening()
    }

    fun cancel() {
        session = false
        stopInternal()
        _ui.value = VoiceUiState()
    }

    fun consumeFinal() {
        _ui.value = VoiceUiState()
    }

    private fun beginListening() {
        if (!session) return
        stopRecognizerOnly()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(listener)
        sr.startListening(listenIntent())
    }

    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2800)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun stopRecognizerOnly() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.setRecognitionListener(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun stopInternal() {
        session = false
        stopRecognizerOnly()
    }

    private fun finishWith(text: String) {
        session = false
        stopRecognizerOnly()
        _ui.value = VoiceUiState(
            state = VoiceState.IDLE,
            finalText = text.ifBlank { null },
            error = if (text.isBlank()) "Não entendi o que foi dito." else null,
        )
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!session) return
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
            if (text.isNotBlank() && session) {
                _ui.value = _ui.value.copy(state = VoiceState.LISTENING, partial = text)
            }
        }

        override fun onEndOfSpeech() {
            if (session) {
                _ui.value = _ui.value.copy(state = VoiceState.UNDERSTANDING)
            }
        }

        override fun onError(error: Int) {
            if (!session) return
            when (VoiceRetry.decide(error, _ui.value.partial, retries)) {
                VoiceRetry.Action.USE_PARTIAL -> finishWith(_ui.value.partial.trim())
                VoiceRetry.Action.RETRY -> {
                    retries += 1
                    _ui.value = _ui.value.copy(state = VoiceState.PREPARING)
                    handler.postDelayed({ if (session) beginListening() }, 250)
                }
                VoiceRetry.Action.FAIL -> {
                    val human = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        -> "Não consegui ouvir. Toque no microfone, espere “Pode falar agora” e fale."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        -> "Preciso da permissão do microfone para ouvir você."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        -> "A fala precisa de um reconhecimento do aparelho. Tente de novo ou escreva o recado."
                        else -> "Não consegui ouvir. Toque de novo ou escreva o recado."
                    }
                    session = false
                    stopRecognizerOnly()
                    _ui.value = VoiceUiState(state = VoiceState.ERROR, error = human)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            if (!session) return
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val used = text.ifBlank { _ui.value.partial }
            finishWith(used.trim())
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

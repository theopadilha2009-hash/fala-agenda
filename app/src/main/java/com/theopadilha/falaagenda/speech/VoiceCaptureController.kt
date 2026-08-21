package com.theopadilha.falaagenda.speech

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    val needSystem: Boolean = false,
)

class VoiceCaptureController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val _ui = MutableStateFlow(VoiceUiState())
    val ui: StateFlow<VoiceUiState> = _ui

    private var session = false
    private var retries = 0
    private var heardReady = false
    private var startedAt = 0L
    private var hostContext: Context = context
    private var backend = VoiceEngine.Capture.IN_APP_DEFAULT

    fun start(host: Context = context) {
        hostContext = host
        val speechHost = unwrapActivity(host)
        backend = VoiceEngine.initial(
            recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(speechHost),
            onDeviceAvailable = onDeviceAvailable(speechHost),
        )
        session = true
        retries = 0
        heardReady = false
        startedAt = SystemClock.elapsedRealtime()
        if (backend == VoiceEngine.Capture.SYSTEM_UI) {
            requestSystemUi()
            return
        }
        _ui.value = VoiceUiState(state = VoiceState.PREPARING)
        beginListening()
    }

    fun cancel() {
        session = false
        stopInternal()
        hostContext = context
        _ui.value = VoiceUiState()
    }

    fun consumeFinal() {
        _ui.value = VoiceUiState()
    }

    fun consumeSystemRequest() {
        _ui.value = _ui.value.copy(needSystem = false)
    }

    fun acceptTranscript(text: String) {
        finishWith(text.trim())
    }

    fun systemListenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Pode falar o recado")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun beginListening() {
        if (!session) return
        destroyRecognizer()
        val speechHost = unwrapActivity(hostContext)
        val sr = runCatching { createRecognizer(speechHost) }.getOrNull()
        if (sr == null) {
            switchOrFail(VoiceRetry.CLIENT)
            return
        }
        recognizer = sr
        sr.setRecognitionListener(listener)
        runCatching { sr.startListening(listenIntent()) }
            .onFailure { switchOrFail(VoiceRetry.CLIENT) }
    }

    private fun createRecognizer(host: Context): SpeechRecognizer {
        if (backend == VoiceEngine.Capture.IN_APP_ON_DEVICE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(host)
            ) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(host)
            }
            error("on-device unavailable")
        }
        return SpeechRecognizer.createSpeechRecognizer(host)
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

    private fun destroyRecognizer() {
        recognizer?.setRecognitionListener(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun stopRecognizerOnly() {
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
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

    private fun requestSystemUi() {
        session = false
        stopRecognizerOnly()
        _ui.value = VoiceUiState(state = VoiceState.PREPARING, needSystem = true)
    }

    private fun failWith(error: Int) {
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

    private fun switchOrFail(error: Int) {
        val next = VoiceEngine.afterFail(
            error = error,
            heardReady = heardReady,
            current = backend,
            onDeviceAvailable = onDeviceAvailable(unwrapActivity(hostContext)),
        )
        when (next) {
            VoiceEngine.Capture.IN_APP_ON_DEVICE -> {
                backend = next
                retries = 0
                heardReady = false
                _ui.value = _ui.value.copy(state = VoiceState.PREPARING, error = null)
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ if (session) beginListening() }, 350)
            }
            VoiceEngine.Capture.SYSTEM_UI -> requestSystemUi()
            VoiceEngine.Capture.IN_APP_DEFAULT, null -> failWith(error)
        }
    }

    private fun onDeviceAvailable(host: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return SpeechRecognizer.isOnDeviceRecognitionAvailable(host)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!session) return
            heardReady = true
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
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            when (VoiceRetry.decide(error, _ui.value.partial, retries, elapsed)) {
                VoiceRetry.Action.USE_PARTIAL -> finishWith(_ui.value.partial.trim())
                VoiceRetry.Action.RETRY -> {
                    retries += 1
                    destroyRecognizer()
                    handler.removeCallbacksAndMessages(null)
                    _ui.value = _ui.value.copy(
                        state = if (heardReady) VoiceState.LISTENING else VoiceState.PREPARING,
                        error = null,
                    )
                    handler.postDelayed({ if (session) beginListening() }, 350)
                }
                VoiceRetry.Action.FAIL -> switchOrFail(error)
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

internal fun unwrapActivity(context: Context): Context {
    var current: Context = context
    val seen = HashSet<Context>()
    while (current is ContextWrapper) {
        if (current is Activity) return current
        if (!seen.add(current)) break
        current = current.baseContext ?: break
    }
    return context
}

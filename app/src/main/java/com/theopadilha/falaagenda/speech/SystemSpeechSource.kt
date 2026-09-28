package com.theopadilha.falaagenda.speech

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * O motor do próprio aparelho. É ele que continua valendo quando não há modelo
 * offline instalado, e é para onde o app volta se o motor offline falhar.
 */
class SystemSpeechSource(
    private val host: Context,
    private val onDevice: Boolean = false,
) : SpeechSource {
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechSource.Listener? = null

    override fun start(listener: SpeechSource.Listener) {
        this.listener = listener
        try {
            val sr = create()
            recognizer = sr
            sr.setRecognitionListener(bridge)
            sr.startListening(listenIntent())
        } catch (_: Exception) {
            // Contexto de Application, OEM quebrado: quem decide o próximo motor é o controller.
            destroy()
            listener.onError(VoiceRetry.CLIENT)
        }
    }

    private fun create(): SpeechRecognizer {
        if (onDevice) {
            // Abaixo da API 31 não existe motor on-device: quem pediu foi a política,
            // e o erro daqui vira troca de motor no controller.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(host)
            }
            error("on-device exige API 31")
        }
        return SpeechRecognizer.createSpeechRecognizer(host)
    }

    override fun cancel() {
        listener = null
        destroy()
    }

    override fun destroy() {
        recognizer?.setRecognitionListener(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
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

    private val bridge = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() {
            listener?.onSpeechBegin()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) listener?.onPartial(text)
        }

        override fun onEndOfSpeech() {
            listener?.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            listener?.onError(error)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            listener?.onFinal(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

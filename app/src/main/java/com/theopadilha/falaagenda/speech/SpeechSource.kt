package com.theopadilha.falaagenda.speech

/**
 * De onde vem o texto do recado. Os códigos de erro são os do SpeechRecognizer
 * (VoiceRetry.CLIENT, SPEECH_TIMEOUT…) — quem não é o motor do sistema traduz os
 * seus para cá, e a política de retry e de troca de motor não precisa saber quem
 * está ouvindo.
 */
interface SpeechSource {
    /** Começa a ouvir. O retorno vem pelo listener; falha de partida vira `onError`. */
    fun start(listener: Listener)

    /** Para de ouvir sem entregar texto. */
    fun cancel()

    /** Libera o que estiver preso (recognizer, microfone, thread). */
    fun destroy()

    interface Listener {
        fun onReady()
        fun onSpeechBegin()
        fun onPartial(text: String)
        fun onEndOfSpeech()
        fun onFinal(text: String)
        fun onError(code: Int)
    }
}

/**
 * O motor que roda no aparelho, sem rede. `null` quando o modelo não está
 * instalado — e aí o app se comporta como antes, delegando ao sistema.
 */
fun interface OfflineSpeech {
    fun newSource(): SpeechSource
}

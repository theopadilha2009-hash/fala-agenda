package com.theopadilha.falaagenda.speech

/**
 * ERROR_SPEECH_TIMEOUT = 6, ERROR_NO_MATCH = 7 (SpeechRecognizer).
 */
object VoiceRetry {
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val MAX_RETRIES = 3

    enum class Action { USE_PARTIAL, RETRY, FAIL }

    fun decide(error: Int, partial: String, retriesAlready: Int): Action {
        val empty = partial.isBlank()
        val retryable = error == SPEECH_TIMEOUT || error == NO_MATCH
        if (!retryable) return Action.FAIL
        if (!empty) return Action.USE_PARTIAL
        if (retriesAlready < MAX_RETRIES) return Action.RETRY
        return Action.FAIL
    }
}

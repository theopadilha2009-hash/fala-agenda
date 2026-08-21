package com.theopadilha.falaagenda.speech

/**
 * ERROR_SPEECH_TIMEOUT = 6, ERROR_NO_MATCH = 7, ERROR_CLIENT = 5, ERROR_RECOGNIZER_BUSY = 8.
 */
object VoiceRetry {
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val BUSY = 8
    const val MAX_RETRIES = 8
    const val GIVE_UP_MS = 8_000L

    enum class Action { USE_PARTIAL, RETRY, FAIL }

    fun decide(
        error: Int,
        partial: String,
        retriesAlready: Int,
        elapsedMs: Long = 0,
    ): Action {
        val retryable = error == SPEECH_TIMEOUT ||
            error == NO_MATCH ||
            error == BUSY ||
            error == CLIENT
        if (!retryable) return Action.FAIL
        if (partial.isNotBlank()) return Action.USE_PARTIAL
        if (elapsedMs < GIVE_UP_MS && retriesAlready < MAX_RETRIES) return Action.RETRY
        return Action.FAIL
    }
}

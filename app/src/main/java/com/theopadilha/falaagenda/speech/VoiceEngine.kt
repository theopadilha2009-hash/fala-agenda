package com.theopadilha.falaagenda.speech

/**
 * Escolhe o motor de fala. ERROR_CLIENT (5) no SpeechRecognizer in-app é o
 * caso clássico de contexto Application / OEM; cai para on-device e, se
 * ainda falhar, para a tela de reconhecimento do próprio celular.
 */
object VoiceEngine {
    enum class Capture { IN_APP_DEFAULT, IN_APP_ON_DEVICE, SYSTEM_UI }

    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val INSUFFICIENT_PERMISSIONS = 9

    fun initial(recognitionAvailable: Boolean, onDeviceAvailable: Boolean): Capture = when {
        recognitionAvailable -> Capture.IN_APP_DEFAULT
        onDeviceAvailable -> Capture.IN_APP_ON_DEVICE
        else -> Capture.SYSTEM_UI
    }

    fun afterFail(
        error: Int,
        heardReady: Boolean,
        current: Capture,
        onDeviceAvailable: Boolean,
    ): Capture? {
        if (error == INSUFFICIENT_PERMISSIONS) return null
        if (current == Capture.IN_APP_DEFAULT &&
            error == VoiceRetry.CLIENT &&
            onDeviceAvailable
        ) {
            return Capture.IN_APP_ON_DEVICE
        }
        val engineBroken = !heardReady ||
            error == VoiceRetry.CLIENT ||
            error == NETWORK ||
            error == NETWORK_TIMEOUT
        return if (engineBroken) Capture.SYSTEM_UI else null
    }
}

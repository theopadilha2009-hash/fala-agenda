package com.theopadilha.falaagenda.speech

import androidx.annotation.VisibleForTesting

/**
 * Escolhe o motor de fala. O offline (modelo instalado no aparelho) vem primeiro
 * porque não depende de rede nem do serviço do fabricante; se ele falhar, o app
 * volta para o caminho de sempre. ERROR_CLIENT (5) no SpeechRecognizer in-app é o
 * caso clássico de contexto Application / OEM; cai para on-device e, se ainda
 * falhar, para a tela de reconhecimento do próprio celular.
 */
object VoiceEngine {
    enum class Capture { OFFLINE_VOSK, IN_APP_DEFAULT, IN_APP_ON_DEVICE, SYSTEM_UI }

    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val INSUFFICIENT_PERMISSIONS = 9

    /**
     * A lib nativa não liga neste processo. `LinkageError` não é falha do momento: a
     * classe fica marcada e toda tentativa seguinte falha igual, sempre depois de pagar
     * a carga quebrada antes de cair no motor do sistema. Era isto o que deixava o
     * microfone lento em *todo* toque — o offline era escolhido, falhava, e a escuta
     * seguinte o escolhia de novo. Condenado uma vez, ele sai da escolha até o processo
     * morrer; o modelo instalado não é tocado, e o motor do sistema assume.
     */
    @Volatile
    private var offlineCondemned = false

    /** Quem viu o `LinkageError` condena: hoje, o [VoskSpeechSource]. */
    fun condemnOffline() {
        offlineCondemned = true
    }

    /**
     * Devolve o offline à escolha. É do teste: no aparelho a falha de ligação vale para
     * o processo inteiro, mas a JVM dos testes é uma só para todos os métodos — sem isto
     * um caso que condena decide a escolha do caso seguinte.
     *
     * `@VisibleForTesting` porque é o único caminho de volta: chamá-la em produção
     * ("tentar o offline de vez em quando") reintroduz exatamente a carga nativa falha
     * por toque que a condenação existe para não pagar.
     */
    @VisibleForTesting
    fun forgetOfflineCondemnation() {
        offlineCondemned = false
    }

    fun initial(
        recognitionAvailable: Boolean,
        onDeviceAvailable: Boolean,
        offlineAvailable: Boolean = false,
    ): Capture = when {
        offlineAvailable && !offlineCondemned -> Capture.OFFLINE_VOSK
        recognitionAvailable -> Capture.IN_APP_DEFAULT
        onDeviceAvailable -> Capture.IN_APP_ON_DEVICE
        else -> Capture.SYSTEM_UI
    }

    fun afterFail(
        error: Int,
        heardReady: Boolean,
        current: Capture,
        onDeviceAvailable: Boolean,
        recognitionAvailable: Boolean = true,
    ): Capture? {
        if (error == INSUFFICIENT_PERMISSIONS) return null
        // O que é nosso não pode ser pior que o do sistema: saiu do offline, volta para
        // o motor de sempre em vez de pular direto para a tela do celular.
        if (current == Capture.OFFLINE_VOSK) {
            return when {
                recognitionAvailable -> Capture.IN_APP_DEFAULT
                onDeviceAvailable -> Capture.IN_APP_ON_DEVICE
                else -> Capture.SYSTEM_UI
            }
        }
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

package com.theopadilha.falaagenda.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceRetryTest {
    @Test
    fun timeoutSemTextoTentaDeNovo() {
        assertThat(VoiceRetry.decide(VoiceRetry.SPEECH_TIMEOUT, "", 0))
            .isEqualTo(VoiceRetry.Action.RETRY)
    }

    @Test
    fun timeoutComParcialUsaTexto() {
        assertThat(VoiceRetry.decide(VoiceRetry.NO_MATCH, "tomar remédio", 0))
            .isEqualTo(VoiceRetry.Action.USE_PARTIAL)
    }

    @Test
    fun depoisDeTresTentativasFalha() {
        assertThat(VoiceRetry.decide(VoiceRetry.SPEECH_TIMEOUT, "", VoiceRetry.MAX_RETRIES))
            .isEqualTo(VoiceRetry.Action.FAIL)
    }

    @Test
    fun outroErroNaoRetenta() {
        assertThat(VoiceRetry.decide(9, "", 0)).isEqualTo(VoiceRetry.Action.FAIL)
    }
}

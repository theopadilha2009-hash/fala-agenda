package com.theopadilha.falaagenda.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceRetryTest {
    @Test
    fun timeoutSemTextoTentaDeNovo() {
        assertThat(VoiceRetry.decide(VoiceRetry.SPEECH_TIMEOUT, "", 0, 200))
            .isEqualTo(VoiceRetry.Action.RETRY)
    }

    @Test
    fun timeoutComParcialUsaTexto() {
        assertThat(VoiceRetry.decide(VoiceRetry.NO_MATCH, "tomar remédio", 0, 400))
            .isEqualTo(VoiceRetry.Action.USE_PARTIAL)
    }

    @Test
    fun dentroDoTempoContinuaTentando() {
        assertThat(VoiceRetry.decide(VoiceRetry.SPEECH_TIMEOUT, "", 5, 3_000))
            .isEqualTo(VoiceRetry.Action.RETRY)
    }

    @Test
    fun depoisDoTempoFalha() {
        assertThat(VoiceRetry.decide(VoiceRetry.SPEECH_TIMEOUT, "", 1, VoiceRetry.GIVE_UP_MS))
            .isEqualTo(VoiceRetry.Action.FAIL)
    }

    @Test
    fun busyTambemRetenta() {
        assertThat(VoiceRetry.decide(VoiceRetry.BUSY, "", 0, 200))
            .isEqualTo(VoiceRetry.Action.RETRY)
    }

    @Test
    fun outroErroNaoRetenta() {
        assertThat(VoiceRetry.decide(9, "", 0, 200)).isEqualTo(VoiceRetry.Action.FAIL)
    }

    @Test
    fun clientNaoRetentaNoMesmoMotor() {
        assertThat(VoiceRetry.decide(VoiceRetry.CLIENT, "", 0, 200))
            .isEqualTo(VoiceRetry.Action.FAIL)
    }

    @Test
    fun clientComParcialUsaTexto() {
        assertThat(VoiceRetry.decide(VoiceRetry.CLIENT, "tomar remédio", 0, 200))
            .isEqualTo(VoiceRetry.Action.USE_PARTIAL)
    }
}

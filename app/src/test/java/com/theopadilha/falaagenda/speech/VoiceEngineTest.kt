package com.theopadilha.falaagenda.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceEngineTest {
    @Test
    fun motorPadraoQuandoReconhecimentoExiste() {
        assertThat(VoiceEngine.initial(recognitionAvailable = true, onDeviceAvailable = false))
            .isEqualTo(VoiceEngine.Capture.IN_APP_DEFAULT)
    }

    @Test
    fun onDeviceQuandoSoEleExiste() {
        assertThat(VoiceEngine.initial(recognitionAvailable = false, onDeviceAvailable = true))
            .isEqualTo(VoiceEngine.Capture.IN_APP_ON_DEVICE)
    }

    @Test
    fun telaDoCelularQuandoNadaExiste() {
        assertThat(VoiceEngine.initial(recognitionAvailable = false, onDeviceAvailable = false))
            .isEqualTo(VoiceEngine.Capture.SYSTEM_UI)
    }

    @Test
    fun clientTentaOnDeviceAntesDaTelaDoSistema() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceRetry.CLIENT,
                heardReady = false,
                current = VoiceEngine.Capture.IN_APP_DEFAULT,
                onDeviceAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.IN_APP_ON_DEVICE)
    }

    @Test
    fun clientSemOnDeviceAbreTelaDoCelular() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceRetry.CLIENT,
                heardReady = false,
                current = VoiceEngine.Capture.IN_APP_DEFAULT,
                onDeviceAvailable = false,
            ),
        ).isEqualTo(VoiceEngine.Capture.SYSTEM_UI)
    }

    @Test
    fun nuncaFicouProntoAbreTelaDoCelular() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceRetry.SPEECH_TIMEOUT,
                heardReady = false,
                current = VoiceEngine.Capture.IN_APP_DEFAULT,
                onDeviceAvailable = false,
            ),
        ).isEqualTo(VoiceEngine.Capture.SYSTEM_UI)
    }

    @Test
    fun timeoutDepoisDeProntoNaoTrocaDeMotor() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceRetry.SPEECH_TIMEOUT,
                heardReady = true,
                current = VoiceEngine.Capture.IN_APP_DEFAULT,
                onDeviceAvailable = true,
            ),
        ).isNull()
    }

    @Test
    fun semPermissaoNaoAbreTelaDoSistema() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceEngine.INSUFFICIENT_PERMISSIONS,
                heardReady = false,
                current = VoiceEngine.Capture.IN_APP_DEFAULT,
                onDeviceAvailable = true,
            ),
        ).isNull()
    }

    @Test
    fun onDeviceQuebradoCaiNaTelaDoCelular() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceRetry.CLIENT,
                heardReady = false,
                current = VoiceEngine.Capture.IN_APP_ON_DEVICE,
                onDeviceAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.SYSTEM_UI)
    }
}

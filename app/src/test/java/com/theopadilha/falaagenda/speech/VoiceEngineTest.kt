package com.theopadilha.falaagenda.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class VoiceEngineTest {
    @Before
    fun setUp() {
        // O motor condenado é estado do processo, e a JVM dos testes é uma só: quem
        // condena num caso não pode decidir a escolha do caso seguinte.
        VoiceEngine.forgetOfflineCondemnation()
    }

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
    fun offlineVemAntesDoMotorDoSistema() {
        assertThat(
            VoiceEngine.initial(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                offlineAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.OFFLINE_VOSK)
    }

    @Test
    fun semModeloOMotorDoSistemaContinuaValendo() {
        assertThat(
            VoiceEngine.initial(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                offlineAvailable = false,
            ),
        ).isEqualTo(VoiceEngine.Capture.IN_APP_DEFAULT)
    }

    @Test
    fun offlineQuebradoVoltaParaOMotorPadrao() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceRetry.CLIENT,
                heardReady = true,
                current = VoiceEngine.Capture.OFFLINE_VOSK,
                onDeviceAvailable = true,
                recognitionAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.IN_APP_DEFAULT)
    }

    @Test
    fun offlineQuebradoSemReconhecimentoVaiParaTelaDoCelular() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceRetry.CLIENT,
                heardReady = true,
                current = VoiceEngine.Capture.OFFLINE_VOSK,
                onDeviceAvailable = false,
                recognitionAvailable = false,
            ),
        ).isEqualTo(VoiceEngine.Capture.SYSTEM_UI)
    }

    @Test
    fun semPermissaoNoOfflineNaoTrocaDeMotor() {
        assertThat(
            VoiceEngine.afterFail(
                error = VoiceEngine.INSUFFICIENT_PERMISSIONS,
                heardReady = false,
                current = VoiceEngine.Capture.OFFLINE_VOSK,
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

    /** Com a lib condenada o modelo instalado deixa de contar: o toque já nasce no sistema. */
    @Test
    fun offlineCondenadoNaoEhMaisEscolhidoNesteProcesso() {
        VoiceEngine.condemnOffline()

        assertThat(
            VoiceEngine.initial(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                offlineAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.IN_APP_DEFAULT)
    }

    @Test
    fun offlineCondenadoSemReconhecimentoVaiParaTelaDoCelular() {
        VoiceEngine.condemnOffline()

        assertThat(
            VoiceEngine.initial(
                recognitionAvailable = false,
                onDeviceAvailable = false,
                offlineAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.SYSTEM_UI)
    }

    /** A condenação é do processo inteiro, não de uma escuta: só o teste a esquece. */
    @Test
    fun esquecerACondenacaoDevolveOMotorOfflineAEscolha() {
        VoiceEngine.condemnOffline()
        VoiceEngine.forgetOfflineCondemnation()

        assertThat(
            VoiceEngine.initial(
                recognitionAvailable = true,
                onDeviceAvailable = true,
                offlineAvailable = true,
            ),
        ).isEqualTo(VoiceEngine.Capture.OFFLINE_VOSK)
    }
}

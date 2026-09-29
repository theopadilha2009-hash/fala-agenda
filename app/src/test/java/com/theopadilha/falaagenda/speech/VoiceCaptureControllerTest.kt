package com.theopadilha.falaagenda.speech

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/** O que ela falou em voz alta não pode ser jogado fora no meio do caminho. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VoiceCaptureControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var controller: VoiceCaptureController

    @Before
    fun setUp() {
        // Sem motor ligado o controller cai na tela do sistema e não há o que dirigir.
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        // O motor condenado é estado do processo, e o sandbox dos testes é um só: quem
        // condena num caso não pode decidir a escolha do caso seguinte.
        VoiceEngine.forgetOfflineCondemnation()
        controller = VoiceCaptureController(context)
        controller.start(context)
        idle()
    }

    @Test
    fun prazoVencidoComParcialEntregaOFalado() {
        ready()
        hear("tomar o remédio de pressão")

        advance(Duration.ofSeconds(21))

        assertThat(controller.ui.value.state).isEqualTo(VoiceState.IDLE)
        assertThat(controller.ui.value.finalText).isEqualTo("tomar o remédio de pressão")
        assertThat(controller.ui.value.error).isNull()
    }

    @Test
    fun prazoVencidoDepoisDePararDeFalarEntregaOFalado() {
        ready()
        hear("cabelo sexta às duas")
        engine().triggerOnEndOfSpeech()

        advance(Duration.ofSeconds(9))

        assertThat(controller.ui.value.state).isEqualTo(VoiceState.IDLE)
        assertThat(controller.ui.value.finalText).isEqualTo("cabelo sexta às duas")
    }

    @Test
    fun prazoVencidoSemParcialContinuaSendoErro() {
        ready()

        advance(Duration.ofSeconds(21))

        assertThat(controller.ui.value.state).isEqualTo(VoiceState.ERROR)
        assertThat(controller.ui.value.finalText).isNull()
        assertThat(controller.ui.value.error).isNotNull()
    }

    @Test
    fun erroDoMotorSemParcialNaoEntregaTextoVazio() {
        ready()
        engine().triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

        advance(Duration.ofSeconds(10))

        assertThat(controller.ui.value.finalText).isNull()
    }

    @Test
    fun parcialDaCapturaAnteriorNaoVazaParaAProxima() {
        ready()
        hear("tomar remédio")
        engine().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        assertThat(controller.ui.value.finalText).isEqualTo("tomar remédio")

        controller.start(context)
        idle()
        assertThat(controller.ui.value.partial).isEmpty()

        ready()
        engine().triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        advance(Duration.ofMillis(400))

        // Sem fala nova, a captura tenta de novo: o texto velho não volta como resultado.
        assertThat(controller.ui.value.finalText).isNull()
        assertThat(controller.ui.value.state).isEqualTo(VoiceState.PREPARING)
    }

    @Test
    fun sessaoCanceladaNaoDeixaPrazoArmado() {
        ready()
        hear("nada disso")

        controller.cancel()
        advance(Duration.ofMillis(500))

        assertThat(controller.ui.value.state).isEqualTo(VoiceState.IDLE)
        assertThat(shadowOf(Looper.getMainLooper()).nextScheduledTaskTime).isEqualTo(Duration.ZERO)
    }

    @Test
    fun capturaConcluidaNaoDeixaPrazoArmado() {
        ready()
        hear("tomar o remédio")
        advance(Duration.ofSeconds(21))

        assertThat(shadowOf(Looper.getMainLooper()).nextScheduledTaskTime).isEqualTo(Duration.ZERO)
    }

    private fun engine() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun ready() {
        engine().triggerOnReadyForSpeech(Bundle())
    }

    private fun hear(text: String) {
        engine().triggerOnPartialResults(
            Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            },
        )
    }

    private fun advance(amount: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(amount)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}

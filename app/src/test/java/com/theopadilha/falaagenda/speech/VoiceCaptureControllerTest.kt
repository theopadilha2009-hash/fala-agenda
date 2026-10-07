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

    /**
     * O parcial que **oscila** não pode deixar a escuta aberta para sempre.
     *
     * O prazo de escuta de 20 s é rearmado a cada parcial novo — de propósito, para não cortar
     * quem dita por mais de 20 s. O parcial do Vosk pode voltar como uma **revisão** do mesmo
     * enunciado ("tomar" → "tomar remédio" → "tomar"); a `VoskUtterance` só deduplica string
     * idêntica, então cada revisão conta como fala nova e reinicia a espera da pausa, e cada
     * revisão também rearma o prazo de 20 s. Com a oscilação contínua nem a pausa fecha nem o
     * prazo vence: a escuta ficaria aberta enquanto ela durasse, que é a mesma classe do
     * defeito que este PR corrige (o app decidindo quando parar de ouvir).
     *
     * O teto duro é a resposta: 60 s contados do `onReady`, que nenhuma revisão estende. E ele
     * não corta em silêncio — o texto que ficou chega **marcado** como cortado.
     */
    @Test
    fun aOscilacaoDoParcialNaoDeixaAEscutaAbertaParaSempre() {
        ready()

        // Revisões alternadas a cada 2 s: cada string nova rearma o prazo de 20 s, e ele nunca
        // vence sozinho. Sem o teto duro, ao fim disto o estado ainda seria LISTENING.
        var revisoes = 0
        while (controller.ui.value.state == VoiceState.LISTENING && revisoes < 100) {
            hear(if (revisoes % 2 == 0) "tomar" else "tomar remédio")
            advance(Duration.ofSeconds(2))
            revisoes += 1
        }

        // A escuta fechou pelo teto duro, não pelo prazo de 20 s: foram mais de 20 s de
        // oscilação contínua, e o prazo de escuta nunca chegou a vencer.
        assertThat(revisoes).isGreaterThan(20)
        assertThat(controller.ui.value.state).isEqualTo(VoiceState.IDLE)
        assertThat(controller.ui.value.truncated).isTrue()
        // O que ela disse até o teto não se joga fora: o último parcial vira o texto, marcado.
        assertThat(controller.ui.value.finalText).isNotEmpty()
    }

    /** O teto é da escuta inteira: passado o limite, ele não fica pendurado para a próxima. */
    @Test
    fun oTetoDuroNaoSobreviveAEscuta() {
        ready()
        hear("tomar remédio")
        advance(Duration.ofSeconds(61))

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

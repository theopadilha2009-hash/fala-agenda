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

/**
 * O recado que o app cortou não pode chegar na tela igual ao recado completo.
 *
 * O defeito medido: ela fala "tomar… remédio… de pressão" e pausa. O motor fecha no
 * primeiro silêncio e o parcial "tomar" é entregue como recado fechado —
 * `state = IDLE`, `finalText = "tomar"`, sem erro nenhum. A tela então pede data e hora
 * para uma tarefa chamada "Tomar", como se fosse o que ela disse. Nada, em lugar nenhum,
 * conta que foi o app que parou de ouvir.
 *
 * Estes casos prendem a distinção: o texto entregue de um parcial (ou pelo prazo vencido)
 * sai marcado, e o texto que veio do fim normal da fala não sai.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VoiceCaptureTruncadaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        VoiceEngine.forgetOfflineCondemnation()
    }

    /**
     * O cenário da caçada, no nível da tela: dois recados com o mesmo `finalText` chegam
     * hoje idênticos, e a tela não tem o que consultar para dizer que um deles foi cortado.
     */
    @Test
    fun recadoCortadoNaoChegaNaTelaIgualAoCompleto() {
        val completo = capturar {
            ready()
            hear("tomar")
            engine().triggerOnEndOfSpeech()
            engine().triggerOnResults(bundleCom("tomar"))
        }
        val cortado = capturar {
            ready()
            hear("tomar")
            engine().triggerOnEndOfSpeech()
            engine().triggerOnResults(bundleCom(""))
        }

        // O texto é o mesmo — é justamente por isso que a marca precisa existir.
        assertThat(cortado.finalText).isEqualTo(completo.finalText)
        assertThat(cortado).isNotEqualTo(completo)
    }

    /** O `onResults` vazio depois de um parcial: o parcial vira recado — e ele foi cortado. */
    @Test
    fun parcialNoLugarDoFinalEhMarcadoComoCortado() {
        val estado = capturar {
            ready()
            hear("tomar")
            engine().triggerOnEndOfSpeech()
            engine().triggerOnResults(bundleCom(""))
        }

        assertThat(estado.finalText).isEqualTo("tomar")
        assertThat(estado.truncated).isTrue()
    }

    /** O prazo de escuta vencido com parcial: entrega o que ouviu, mas não mente que acabou. */
    @Test
    fun prazoVencidoComParcialEhMarcadoComoCortado() {
        val estado = capturar {
            ready()
            hear("tomar")
            advance(Duration.ofSeconds(21))
        }

        assertThat(estado.finalText).isEqualTo("tomar")
        assertThat(estado.truncated).isTrue()
    }

    /** O erro do motor com parcial (o caminho do `VoiceRetry.USE_PARTIAL`). */
    @Test
    fun erroDoMotorComParcialEhMarcadoComoCortado() {
        val estado = capturar {
            ready()
            hear("tomar")
            engine().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        }

        assertThat(estado.finalText).isEqualTo("tomar")
        assertThat(estado.truncated).isTrue()
    }

    /** O fim normal da fala continua sendo um recado completo. */
    @Test
    fun finalCompletoNaoEhMarcadoComoCortado() {
        val estado = capturar {
            ready()
            hear("tomar remédio de pressão amanhã às oito")
            engine().triggerOnEndOfSpeech()
            engine().triggerOnResults(bundleCom("tomar remédio de pressão amanhã às oito"))
        }

        assertThat(estado.finalText).isEqualTo("tomar remédio de pressão amanhã às oito")
        assertThat(estado.truncated).isFalse()
    }

    /**
     * O aviso não pode sumir com o texto: a tela consome o `finalText` para mandar o recado
     * ao parser, e é depois disso — na espera e na confirmação — que ela precisa ler que a
     * escuta foi interrompida.
     */
    @Test
    fun oAvisoDeCorteSobreviveAoConsumoDoTexto() {
        val controller = VoiceCaptureController(context)
        controller.start(context)
        idle()
        ready()
        hear("tomar")
        engine().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)

        controller.consumeFinal()

        assertThat(controller.ui.value.finalText).isNull()
        assertThat(controller.ui.value.truncated).isTrue()
    }

    /** A escuta seguinte é outra: o aviso do recado anterior não pode ficar na tela. */
    @Test
    fun aEscutaSeguinteNaoHerdaOAvisoDeCorte() {
        val controller = VoiceCaptureController(context)
        controller.start(context)
        idle()
        ready()
        hear("tomar")
        engine().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)

        controller.start(context)
        idle()

        assertThat(controller.ui.value.truncated).isFalse()
    }

    /** Escuta do começo ao fim e devolve o estado que a tela recebe. */
    private fun capturar(roteiro: () -> Unit): VoiceUiState {
        val controller = VoiceCaptureController(context)
        controller.start(context)
        idle()
        roteiro()
        idle()
        return controller.ui.value
    }

    private fun engine() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun ready() {
        engine().triggerOnReadyForSpeech(Bundle())
    }

    private fun hear(text: String) {
        engine().triggerOnPartialResults(bundleCom(text))
    }

    private fun bundleCom(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun advance(amount: Duration) {
        shadowOf(Looper.getMainLooper()).idleFor(amount)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}

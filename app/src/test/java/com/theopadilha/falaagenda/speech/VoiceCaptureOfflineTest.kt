package com.theopadilha.falaagenda.speech

import android.app.Application
import android.content.Context
import android.os.Looper
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
 * Com o modelo instalado, quem ouve é o motor offline. O do sistema continua ali
 * como rede de segurança: se o offline cair, é para ele que o app volta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VoiceCaptureOfflineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        // O motor condenado é estado do processo, e o sandbox dos testes é um só: quem
        // condena num caso não pode decidir a escolha do caso seguinte.
        VoiceEngine.forgetOfflineCondemnation()
    }

    @Test
    fun comModeloAEscutaEOfflineSemTocarNoMotorDoSistema() {
        val fake = FakeSource()
        val controller = VoiceCaptureController(context, offline = { OfflineSpeech { fake } })

        controller.start(context)
        idle()

        assertThat(fake.started).isTrue()
        assertThat(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isNull()
    }

    @Test
    fun oParcialDoOfflineChegaNaTela() {
        val fake = FakeSource()
        val controller = VoiceCaptureController(context, offline = { OfflineSpeech { fake } })
        controller.start(context)
        idle()

        fake.listener?.onReady()
        fake.listener?.onPartial("tomar remédio")
        idle()

        assertThat(controller.ui.value.state).isEqualTo(VoiceState.LISTENING)
        assertThat(controller.ui.value.partial).isEqualTo("tomar remédio")
    }

    @Test
    fun oFinalDoOfflineFechaORecado() {
        val fake = FakeSource()
        val controller = VoiceCaptureController(context, offline = { OfflineSpeech { fake } })
        controller.start(context)
        idle()

        fake.listener?.onReady()
        fake.listener?.onEndOfSpeech()
        fake.listener?.onFinal("tomar remédio amanhã às oito")
        idle()

        assertThat(controller.ui.value.state).isEqualTo(VoiceState.IDLE)
        assertThat(controller.ui.value.finalText).isEqualTo("tomar remédio amanhã às oito")
    }

    @Test
    fun offlineQueFalhaVoltaParaOMotorDoSistema() {
        val fake = FakeSource()
        val controller = VoiceCaptureController(context, offline = { OfflineSpeech { fake } })
        controller.start(context)
        idle()

        fake.listener?.onError(VoiceRetry.CLIENT)
        idle()
        advance(Duration.ofMillis(400))

        // Voltou para o caminho de sempre: um recognizer do aparelho foi criado.
        assertThat(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isNotNull()
    }

    @Test
    fun modeloQueChegaDepoisPassaAValerNaProximaEscuta() {
        val fake = FakeSource()
        var baixado = false
        val controller = VoiceCaptureController(
            context,
            offline = { if (baixado) OfflineSpeech { fake } else null },
        )

        controller.start(context)
        idle()
        assertThat(fake.started).isFalse()

        // O download terminou com o app aberto: a escuta seguinte já é a offline.
        baixado = true
        controller.cancel()
        controller.start(context)
        idle()

        assertThat(fake.started).isTrue()
    }

    /**
     * O toque seguinte ao que descobriu a lib quebrada não paga a tentativa de novo: o
     * offline já está fora da escolha deste processo, e o microfone abre no motor de
     * sempre. Era isto que ela sentia como "o microfone demora toda vez".
     */
    @Test
    fun depoisDaLibQuebradaAProximaEscutaNemTentaOMotorOffline() {
        VoiceEngine.condemnOffline()
        val fake = FakeSource()
        val controller = VoiceCaptureController(context, offline = { OfflineSpeech { fake } })

        controller.start(context)
        idle()

        assertThat(fake.started).isFalse()
        assertThat(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isNotNull()
    }

    /**
     * O modelo offline é pedido pelo toque no microfone — por onde passam o botão da
     * home, o atalho, o "Falar" do widget e o ícone do lançador. Abrir o app não pede
     * nada: era 31 MB por abertura.
     */
    @Test
    fun pedirVozPedeOModeloOffline() {
        var pedidos = 0
        val controller = VoiceCaptureController(context, requestOfflineModel = { pedidos += 1 })

        controller.start(context)
        idle()

        assertThat(pedidos).isEqualTo(1)
    }

    /** A troca de motor no meio da escuta não é um pedido novo — senão vira laço. */
    @Test
    fun trocarDeMotorDentroDaMesmaEscutaNaoPedeDeNovo() {
        var pedidos = 0
        val fake = FakeSource()
        val controller = VoiceCaptureController(
            context,
            offline = { OfflineSpeech { fake } },
            requestOfflineModel = { pedidos += 1 },
        )

        controller.start(context)
        idle()
        fake.listener?.onError(VoiceRetry.CLIENT)
        idle()
        advance(Duration.ofMillis(400))

        assertThat(pedidos).isEqualTo(1)
    }

    private class FakeSource : SpeechSource {
        var started = false
        var listener: SpeechSource.Listener? = null

        override fun start(listener: SpeechSource.Listener) {
            started = true
            this.listener = listener
        }

        override fun cancel() = Unit
        override fun destroy() = Unit
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(amount: Duration) = shadowOf(Looper.getMainLooper()).idleFor(amount)
}

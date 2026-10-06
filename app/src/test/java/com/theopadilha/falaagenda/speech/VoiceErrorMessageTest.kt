package com.theopadilha.falaagenda.speech

import android.app.Application
import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O que ela lê quando a escuta falha.
 *
 * Quem lê é uma pessoa idosa que fala com o app — não um dev. O texto de rede dizia "A fala
 * precisa de um reconhecimento do aparelho", que é jargão e não diz o que fazer; o de
 * timeout mandava "Toque no microfone, espere “Pode falar agora” e fale", comprido demais
 * para uma tela que ela lê com pressa. Estes casos prendem as frases curtas: reverter para
 * o jargão deixa a suíte vermelha.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VoiceErrorMessageTest {

    /** O ramo de "não ouvi nada": curto e concreto, sem o roteiro de toques. */
    @Test
    fun semFalaMandaFalarPertoDoMicrofone() {
        val esperado = "Não consegui ouvir. Fale mais perto do microfone."

        assertThat(voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH)).isEqualTo(esperado)
        assertThat(voiceErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)).isEqualTo(esperado)
    }

    /**
     * O ramo de rede continua sendo o de rede — a causa não mudou —, mas sem o jargão.
     */
    @Test
    fun semRedeNaoFalaEmReconhecimentoDoAparelho() {
        listOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT).forEach { erro ->
            val texto = voiceErrorMessage(erro)
            assertThat(texto).doesNotContain("reconhecimento do aparelho")
            assertThat(texto).isEqualTo("Não consegui ouvir. Tente de novo ou escreva o recado.")
        }
    }

    /** A permissão continua nomeada: é o único ramo em que ela tem de ir nos ajustes. */
    @Test
    fun semPermissaoPedeOPermissaoDoMicrofone() {
        assertThat(voiceErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
            .isEqualTo("Preciso da permissão do microfone para ouvir você.")
    }

    /**
     * Nenhuma das frases que ela lê carrega jargão de dev nem aspas aninhadas — as duas
     * coisas que a leitura de tela lê mal e que este conserto tirou.
     */
    @Test
    fun nenhumaFraseTemJargaoNemAspas() {
        val erros = listOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            999,
        )

        erros.forEach { erro ->
            val texto = voiceErrorMessage(erro)
            assertThat(texto).doesNotContain("reconhecimento")
            assertThat(texto).doesNotContain("“")
            assertThat(texto).doesNotContain("”")
        }
    }
}

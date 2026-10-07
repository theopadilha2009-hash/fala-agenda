package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * O motor de verdade: a única classe desta voz que fala com o `TextToSpeech` do aparelho.
 *
 * Ele era o arquivo sem teste do PR, e a metade que importa aqui é a resposta do [VozDoAparelho.falar]:
 * é ela que separa "o motor aceitou a frase" de "o motor recusou", e é isso que a barra usa para
 * não afirmar uma fala que não houve. O que dá para exercitar no Robolectric é o aceite — o
 * `ShadowTextToSpeech` sempre devolve `SUCCESS` no `speak`, então o ramo da recusa (`ERROR`/`catch`)
 * é declarado sem teste; quem o prende é a costura, no `LembreteFaladoServiceTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VozDoAparelhoTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    private val frase = "Está na hora. Tomar remédio."

    @After
    fun devolverOMotor() {
        ShadowTextToSpeech.reset()
    }

    /** O idioma dela, instalado no motor de mentira do Robolectric. */
    private fun temVozEmPortugues() {
        ShadowTextToSpeech.addLanguageAvailability(LOCALE_DA_VOZ)
    }

    /**
     * Entrega o `onInit` que o aparelho mandaria. O `ShadowTextToSpeech` não o dispara sozinho, e
     * sem ele a pergunta de "sabe falar?" fica pendurada — que é justamente o caso do motor que
     * nunca sobe.
     */
    private fun entregaOOnInit(status: Int) {
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(status)
    }

    /**
     * Com a voz em português disponível, o motor fica pronto e a frase **sai**: o `falar` responde
     * `true` e a frase está no motor. É esta resposta que a barra usa para poder dizer "avisando em
     * voz alta" — sem ela, a política não tem como saber se a fala existiu.
     */
    @Test
    fun comVozEmPortuguesOMotorFicaProntoEAFraseSai() {
        temVozEmPortugues()
        val voz = VozDoAparelho(contexto)
        var pronto: Boolean? = null
        voz.quandoPronto { pronto = it }
        entregaOOnInit(TextToSpeech.SUCCESS)
        assertThat(pronto).isTrue()

        var terminou: String? = null
        val saiu = voz.falar(frase, "fala-0") { terminou = it }

        assertThat(saiu).isTrue()
        // O fim da fala só chega pelo motor, e não na hora do pedido: o `aoTerminar` guardado é o
        // que o `UtteranceProgressListener` vai chamar depois.
        assertThat(terminou).isNull()
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertThat(shadowOf(motor).spokenTextList).contains(frase)
    }

    /**
     * O aparelho não tem voz em português do Brasil — só inglês instalado, ou nenhuma voz. O motor
     * **não** fica pronto, e é isso que faz a política sair do caminho sem travar em vez de falar
     * "Está na hora do seu remédio" com sotaque de quem não sabe o que está dizendo.
     */
    @Test
    fun semVozEmPortuguesOMotorNaoFicaPronto() {
        val voz = VozDoAparelho(contexto)
        var pronto: Boolean? = null
        voz.quandoPronto { pronto = it }

        // O `onInit` do aparelho chega agora, com sucesso: o motor subiu, e mesmo assim ele não
        // sabe falar com ela. É a segunda pergunta do `configurar` — `isLanguageAvailable` abaixo
        // de `LANG_AVAILABLE` — que responde `false`.
        entregaOOnInit(TextToSpeech.SUCCESS)

        assertThat(pronto).isFalse()
    }

    /**
     * O motor já foi fechado — o serviço morreu, ou um disparo novo abandonou este. `falar` aqui não
     * fala e **não** avisa o fim: não há motor para terminar, e o `false` é o que conta a quem pediu
     * que nada saiu.
     */
    @Test
    fun motorFechadoNaoFalaNemAvisaOFim() {
        temVozEmPortugues()
        val voz = VozDoAparelho(contexto)
        voz.soltar()

        var terminou = false
        val saiu = voz.falar(frase, "fala-0") { terminou = true }

        assertThat(saiu).isFalse()
        assertThat(terminou).isFalse()
    }
}

package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * O motor de verdade: a única classe desta voz que fala com o `TextToSpeech` do aparelho.
 *
 * Ele era o arquivo sem teste do PR, e a metade que importa aqui é o aviso de **saída** do
 * [VozDoAparelho]: é ele que separa "o motor aceitou a frase" de "a fala saiu", e é isso que a
 * barra usa para não afirmar uma fala que não houve.
 *
 * São duas classes, e não uma, por um limite do Robolectric: o `@Config(shadows = [...])` vale para
 * a **classe inteira**, então um shadow que recusa o `speak` recusaria também os casos de aceite.
 * Aqui, com o shadow padrão, o motor aceita; em [VozDoAparelhoQueRecusaTest] ele recusa.
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
     * Com a voz em português disponível, o motor fica pronto e a frase **sai**: o motor avisa que
     * começou a falar, e é esse aviso que a barra usa para poder dizer "avisando em voz alta".
     */
    @Test
    fun comVozEmPortuguesOMotorFicaProntoEAFraseSai() {
        val voz = VozDoAparelho(contexto)
        // A pergunta vem antes do idioma estar disponível, e o `onInit` depois: é este caminho —
        // o de verdade, em que o motor ainda está subindo — que faz o `configurar()` rodar e
        // instalar o `UtteranceProgressListener` no motor. Com o idioma já disponível, o
        // `quandoPronto` responde pelo caminho curto e o listener nem chega a ser instalado.
        var pronto: Boolean? = null
        voz.quandoPronto { pronto = it }

        temVozEmPortugues()
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)
        assertThat(pronto).isTrue()

        var saidas = 0
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { }

        assertThat(shadowOf(motor).spokenTextList).contains(frase)

        // O motor de verdade avisa que começou a falar — é o `onStart` dele.
        shadowOf(motor).utteranceProgressListener.onStart("fala-0")
        assertThat(saidas).isEqualTo(1)
    }

    /**
     * Um motor normal avisa o começo **e** o fim da mesma fala: `onStart` e depois `onDone`. O
     * aviso de saída sai **uma vez** — o `onDone` só repete o aviso quando o `onStart` não veio
     * (motor de fabricante que não manda o começo). Sem a guarda, o mesmo `aoSair` sairia duas
     * vezes por fala, e o contrato de "o motor começou a falar" deixaria de ser um por fala.
     */
    @Test
    fun oAvisoDeSaidaSaiUmaVezPorFalaMesmoComOnStartEOnDone() {
        val voz = VozDoAparelho(contexto)
        voz.quandoPronto { }
        temVozEmPortugues()
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)

        var saidas = 0
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { }

        val listener = shadowOf(motor).utteranceProgressListener
        listener.onStart("fala-0")
        listener.onDone("fala-0")

        assertThat(saidas).isEqualTo(1)
    }

    /**
     * Motor que só avisa o fim, sem o `onStart` — detalhe de fabricante. O aviso de saída não pode
     * se perder por causa disso: a barra ficaria sem a verdade, e o `onDone` é o fallback.
     */
    @Test
    fun motorQueSoAvisaOFimAindaAssimTemASaidaAnunciada() {
        val voz = VozDoAparelho(contexto)
        voz.quandoPronto { }
        temVozEmPortugues()
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)

        var saidas = 0
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { }

        shadowOf(motor).utteranceProgressListener.onDone("fala-0")

        assertThat(saidas).isEqualTo(1)
    }

    /**
     * O caminho normal de todo `TextToSpeech`: o motor avisa `onStart` e **depois** `onDone`. O fim
     * tem que chegar a quem pediu a fala — é dele que a política depende para agendar a **segunda**
     * repetição. Sem este aviso, `AvisoFalado.terminou` nunca roda, a escada para na primeira e o
     * aviso fica de pé até o prazo: a segunda fala — o motivo de `FALAS_POR_AVISO` existir — nunca
     * sai, e é a queixa dela na metade da repetição.
     *
     * Os três fakes da suíte avisam o fim **dentro do `falar`** e nunca passam pelo
     * `UtteranceProgressListener`; este é o único teste que dirige o listener de verdade, e por
     * isso é ele que prende o `aoTerminar` do `onDone`.
     */
    @Test
    fun oOnDoneAvisaOFimAQuemPediuAFala() {
        val voz = VozDoAparelho(contexto)
        voz.quandoPronto { }
        temVozEmPortugues()
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)

        var saidas = 0
        var terminou: String? = null
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { terminou = it }

        val listener = shadowOf(motor).utteranceProgressListener
        listener.onStart("fala-0")
        assertThat(terminou).isNull()
        listener.onDone("fala-0")

        assertThat(saidas).isEqualTo(1)
        assertThat(terminou).isEqualTo("fala-0")
    }

    /**
     * O overload deprecado do `onError` é o que a classe base exige; motor antigo pode chamá-lo em
     * vez do que tem `errorCode`. Nos dois, o fim é avisado: sem ele a política esperaria para
     * sempre por um `onDone` que não vem.
     */
    @Test
    fun oOnErrorDeprecadoTambemAvisaOFim() {
        val voz = VozDoAparelho(contexto)
        voz.quandoPronto { }
        temVozEmPortugues()
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)

        var terminou: String? = null
        voz.falar(frase, "fala-0", aoSair = { }) { terminou = it }

        @Suppress("DEPRECATION")
        shadowOf(motor).utteranceProgressListener.onError("fala-0")

        assertThat(terminou).isEqualTo("fala-0")
    }

    /**
     * O aviso de saída é da fala da vez. Um `onStart` atrasado de uma fala já substituída — o
     * disparo novo pediu outra frase — não pode anunciar a voz que está no ar agora.
     *
     * Hoje o id vem de um contador monotônico em `AvisoFalado`, então um aviso antigo nunca casa
     * com o id atual: é defesa em profundidade, e é por isso que este teste existe — sem ele a
     * guarda não estava presa por nada.
     */
    @Test
    fun oAvisoDeSaidaDeUmaFalaJaSubstituidaNaoAnuncia() {
        val voz = VozDoAparelho(contexto)
        voz.quandoPronto { }
        temVozEmPortugues()
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)

        var saidas = 0
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { }
        // A fala da vez passou a ser outra.
        voz.falar(frase, "fala-1", aoSair = { saidas++ }) { }

        val listener = shadowOf(motor).utteranceProgressListener
        listener.onStart("fala-0")

        assertThat(saidas).isEqualTo(0)

        // E a fala da vez continua anunciando normalmente.
        listener.onStart("fala-1")
        assertThat(saidas).isEqualTo(1)
    }

    /**
     * O aparelho não tem voz em português do Brasil — só inglês instalado, ou nenhuma voz. O motor
     * **não** fica pronto, e é isso que faz a política sair do caminho sem travar.
     */
    @Test
    fun semVozEmPortuguesOMotorNaoFicaPronto() {
        val voz = VozDoAparelho(contexto)
        var pronto: Boolean? = null
        voz.quandoPronto { pronto = it }

        entregaOOnInit(TextToSpeech.SUCCESS)

        assertThat(pronto).isFalse()
    }

    /**
     * O motor já foi fechado — o serviço morreu, ou um disparo novo abandonou este. Não há fala e a
     * saída não é avisada; o fim é, para a política não esperar por um aviso que nunca vem.
     */
    @Test
    fun motorFechadoNaoFalaNemAvisaASaida() {
        temVozEmPortugues()
        val voz = VozDoAparelho(contexto)
        voz.soltar()

        var saidas = 0
        var terminou = false
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { terminou = true }

        assertThat(saidas).isEqualTo(0)
        assertThat(terminou).isTrue()
    }
}

/**
 * O mesmo motor, com um `TextToSpeech` que **recusa** a frase: o `speak` devolve o código de erro e
 * nenhum som sai. É a voz do pt-BR corrompida ou parcialmente baixada, o idioma listado sem dado de
 * fala, o motor ocupado — a queixa dela, literal.
 *
 * O `ShadowTextToSpeech` do Robolectric sempre aceita (`speak` termina em `iconst_0; ireturn`), e
 * por isso este caminho precisa de um shadow próprio. Ele vale só para esta classe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
    shadows = [VozDoAparelhoQueRecusaTest.ShadowTextToSpeechQueRecusa::class],
)
class VozDoAparelhoQueRecusaTest {
    @Implements(TextToSpeech::class)
    class ShadowTextToSpeechQueRecusa : ShadowTextToSpeech() {
        @Implementation
        override fun speak(
            text: CharSequence?,
            queueMode: Int,
            params: Bundle?,
            utteranceId: String?,
        ): Int = TextToSpeech.ERROR
    }

    private val contexto: Context = ApplicationProvider.getApplicationContext()

    private val frase = "Está na hora. Tomar remédio."

    @After
    fun devolverOMotor() {
        ShadowTextToSpeech.reset()
    }

    /**
     * O motor **recusa** a frase: nenhum som saiu, então a saída não pode ser avisada — é o
     * `aoSair` que a barra usa para dizer "avisando em voz alta". O fim, sim, é avisado: a política
     * precisa dele para fechar a conta em vez de esperar até o prazo.
     */
    @Test
    fun oMotorQueRecusaAFraseNaoAvisaASaidaEAvisaOFim() {
        ShadowTextToSpeech.addLanguageAvailability(LOCALE_DA_VOZ)
        val voz = VozDoAparelho(contexto)
        voz.quandoPronto { }
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)

        var saidas = 0
        var terminou: String? = null
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { terminou = it }

        assertThat(saidas).isEqualTo(0)
        assertThat(terminou).isEqualTo("fala-0")
    }
}

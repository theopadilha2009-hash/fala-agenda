package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.content.Context
import android.media.AudioManager
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
 * O aparelho mudo **não** economiza a síntese: a frase continua sendo pedida ao motor e o fim
 * continua sendo avisado.
 *
 * Este arquivo existe por causa de um buraco medido no review do #96. O [VozDoAparelho] suprime a
 * **afirmação** ("avisando em voz alta") quando o `STREAM_ALARM` está em zero — e o KDoc do
 * [VozMudaNaoAfirmaQueFalouTest] diz, com todas as letras, que a **tentativa** segue de pé: "a voz
 * continua sendo pedida (o motor aceita a frase e o fim é avisado)". Nada prendia essa metade.
 *
 * O review inseriu `if (!streamDaVozAudivel()) { aoTerminar(id); return }` logo depois de
 * `idDaFala = id` — o "economizar a síntese" que parece uma melhoria óbvia — e rodou a suíte
 * inteira: **verde**. O invariante não tinha quem o prendesse, e o próximo a mexer ali derrubaria o
 * contrato em silêncio.
 *
 * Por que a tentativa precisa ficar de pé, e não é zelo: a [AvisoFalado] fecha a conta pelo
 * `aoTerminar`. Um atalho que devolve o fim sem passar pelo motor faria a política contar uma fala
 * que não houve, e a escada de repetições andaria no vazio — além de o dia em que o aparelho sai do
 * mudo deixar de ter o que repetir. E o `onStart` do motor chega mesmo com o stream em zero: quem
 * decide se a onda sai é o aparelho, não o aplicativo.
 *
 * As duas asserções são positivas e exatas — a frase **está** na lista de falas pedidas, e o fim
 * **chega** com o id da fala. E o `terminou` é conferido **antes** do `onDone` de propósito: o fim
 * tem que vir do motor, e não de um atalho que responde por ele.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VozMudaAindaPedeAFalaTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val audio: AudioManager = contexto.getSystemService(AudioManager::class.java)

    private val frase = "Está na hora. Tomar remédio."

    @After
    fun devolverOMotor() {
        ShadowTextToSpeech.reset()
    }

    private fun volumeDoAlarme(valor: Int) =
        audio.setStreamVolume(AudioManager.STREAM_ALARM, valor, 0)

    /**
     * O motor de verdade, com o idioma instalado e o `onInit` entregue — o caminho em que o
     * `configurar()` roda e instala o `UtteranceProgressListener`.
     *
     * A ordem é a mesma do [VozDoAparelhoTest], e não é detalhe: a pergunta de "sabe falar?" vem
     * **antes** de o idioma estar disponível, senão o `quandoPronto` responde pelo caminho curto e o
     * listener nem chega a existir — e o `onDone` não teria a quem avisar o fim.
     */
    private fun vozComOMotorDeVerdade(): Pair<VozDoAparelho, TextToSpeech> {
        val voz = VozDoAparelho(contexto)
        voz.quandoPronto { }
        ShadowTextToSpeech.addLanguageAvailability(LOCALE_DA_VOZ)
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)
        return voz to motor
    }

    /**
     * O contrato inteiro num aparelho calado: a frase vai para o motor, a saída **não** é anunciada
     * (a barra não afirma o que não saiu), e o fim chega quando o motor o dá.
     *
     * A asserção da lista de falas é a que mata o atalho "economizar a síntese": com ele o `speak`
     * nunca é chamado e a lista fica vazia. A do `terminou` logo depois do `falar` é a segunda
     * metade, e é ela que impede o fim de mentira — um `aoTerminar` devolvido na hora passaria na
     * asserção final sozinho.
     */
    @Test
    fun noAparelhoMudoAFraseAindaEhPedidaEAvisadaAteOFim() {
        volumeDoAlarme(0)
        val (voz, motor) = vozComOMotorDeVerdade()

        var saidas = 0
        var terminou: String? = null
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { terminou = it }

        // A frase foi pedida ao motor, mesmo com o aparelho sem por onde soar.
        assertThat(shadowOf(motor).spokenTextList).contains(frase)
        // A barra não afirma uma fala que o aparelho não tem como emitir.
        assertThat(saidas).isEqualTo(0)
        // E o fim ainda não veio: ele é do motor, não de um atalho que responde por ele.
        assertThat(terminou).isNull()

        shadowOf(motor).utteranceProgressListener.onDone("fala-0")

        assertThat(terminou).isEqualTo("fala-0")
    }

    /**
     * A fronteira do mesmo eixo: com o alarme audível, a frase também é pedida — e aí a saída
     * **é** anunciada. Sem este caso, um `aoSair` cortado de vez passaria no de cima; com ele, o
     * que se prova é que o aparelho mudo mexe só na afirmação, e não na fala.
     */
    @Test
    fun comOAparelhoAudivelAFraseEhPedidaEASaidaEhAnunciada() {
        volumeDoAlarme(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
        val (voz, motor) = vozComOMotorDeVerdade()

        var saidas = 0
        var terminou: String? = null
        voz.falar(frase, "fala-0", aoSair = { saidas++ }) { terminou = it }

        assertThat(shadowOf(motor).spokenTextList).contains(frase)

        shadowOf(motor).utteranceProgressListener.onStart("fala-0")
        shadowOf(motor).utteranceProgressListener.onDone("fala-0")

        assertThat(saidas).isEqualTo(1)
        assertThat(terminou).isEqualTo("fala-0")
    }
}

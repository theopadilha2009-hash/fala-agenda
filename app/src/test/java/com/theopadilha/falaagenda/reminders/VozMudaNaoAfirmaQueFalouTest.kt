package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.R
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/**
 * A barra só afirma "Avisando em voz alta" quando a fala tem por onde sair.
 *
 * A queixa dela é literal — "o áudio nunca funciona" — e a medição do caminho inteiro mostrou o
 * estado exato em que o aplicativo diz o contrário: **canal saudável, alarme do aparelho no volume
 * zero**. O lembrete sai, o motor sintetiza a frase, o `onStart` dele chega e a barra passa a dizer
 * que está falando — no mesmo `STREAM_ALARM` que está em zero. A frase existe para o motor e não
 * existe para ela, e é o aplicativo dizendo na cara dela que falou.
 *
 * O que este arquivo prende é a **afirmação**, e não a tentativa: a voz continua sendo pedida (o
 * motor aceita a frase e o fim é avisado), mas a barra não pode anunciar uma fala que o aparelho
 * não tem por onde emitir. Quem já avisa que o aparelho está mudo é o cartão da home
 * (`ReminderAlerts.APARELHO_MUDO`); o que faltava era o caminho da voz parar de mentir.
 *
 * A outra metade — a tentativa seguir de pé — tem arquivo próprio: [VozMudaAindaPedeAFalaTest]. Ela
 * ficou sem quem a prendesse na primeira versão deste trabalho, e o review mediu o custo disso: o
 * atalho "economizar a síntese" no aparelho mudo derrubava o contrato com a suíte inteira verde.
 *
 * O motor é o de **verdade** ([VozDoAparelho]) sobre o `ShadowTextToSpeech`, e o stream é o de
 * verdade: o que o teste move é o volume do `STREAM_ALARM`, que é o fato do aparelho. O `aoFalar`
 * que o serviço usa é lido do **efeito** — o título que está de fato na barra —, e não de um
 * campo interno.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class VozMudaNaoAfirmaQueFalouTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val gerente: NotificationManager =
        contexto.getSystemService(NotificationManager::class.java)
    private val audio: AudioManager = contexto.getSystemService(AudioManager::class.java)
    private val ocorrencia = "remedio:2026-10-07"
    private val titulo = "Tomar remédio"

    @After
    fun devolverOMotorEOVolume() {
        ShadowTextToSpeech.reset()
        LembreteFaladoService.criarVoz = { VozDoAparelho(it) }
        LembreteFaladoService.subirEmPrimeiroPlano = { it.tentarSubirEmPrimeiroPlano() }
    }

    private fun volumeDoAlarme(valor: Int) =
        audio.setStreamVolume(AudioManager.STREAM_ALARM, valor, 0)

    /** O título que está de fato na barra, e não o que o serviço guardou por último. */
    private fun titulosNaBarra(): List<String> =
        shadowOf(gerente).allNotifications
            .mapNotNull { it.extras?.getString(Notification.EXTRA_TITLE) }

    private fun afirmaQueEstaFalando(): Boolean =
        titulosNaBarra().contains(contexto.getString(R.string.reminder_speaking_title))

    /**
     * Sobe o serviço de verdade, com o motor de voz de verdade, e leva a fala até o `onStart` do
     * motor — o instante em que a barra decide se afirma.
     *
     * A ordem é a mesma do `VozDoAparelhoTest`, e não é detalhe: a pergunta de "sabe falar?" vem
     * **antes** de o idioma estar disponível, para o `configurar()` rodar e instalar o
     * `UtteranceProgressListener`. Com o idioma já disponível, o `quandoPronto` responde pelo
     * caminho curto e o listener nem chega a existir — e o `onStart` não teria a quem avisar.
     */
    private fun falarComOMotorDeVerdade() {
        val intent = LembreteFaladoService.intentPara(contexto, ocorrencia, titulo)
        val servico = Robolectric.buildService(LembreteFaladoService::class.java, intent).create()
        servico.get().onStartCommand(intent, 0, 1)

        ShadowTextToSpeech.addLanguageAvailability(LOCALE_DA_VOZ)
        val motor = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(motor).onInitListener.onInit(TextToSpeech.SUCCESS)
        // O motor começou a falar — o `onStart` dele, que é o aviso de saída.
        shadowOf(motor).utteranceProgressListener.onStart("fala-0")
    }

    /**
     * O defeito, no estado exato que ela vive: o alarme do aparelho está no zero e a barra afirma
     * que está falando. A asserção positiva junto é o que impede o teste vacuoso — a notificação da
     * voz existe (o título neutro do canal está lá); o que não pode existir é a afirmação.
     */
    @Test
    fun comOAlarmeZeradoABarraNaoAfirmaQueFalou() {
        volumeDoAlarme(0)

        falarComOMotorDeVerdade()

        assertThat(titulosNaBarra())
            .contains(contexto.getString(R.string.notification_channel_voice))
        assertThat(afirmaQueEstaFalando()).isFalse()
    }

    /**
     * A outra metade, e é ela que impede o conserto por omissão: com o alarme audível a barra
     * **diz** que está falando. Sem este caso, apagar a afirmação de vez passaria no teste de cima e
     * deixaria a notificação sem contar o que está acontecendo.
     */
    @Test
    fun comOAlarmeAudivelABarraAfirmaQueFalou() {
        volumeDoAlarme(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))

        falarComOMotorDeVerdade()

        assertThat(afirmaQueEstaFalando()).isTrue()
    }

    /**
     * A fronteira, e ela é o limite honesto do critério: **volume baixo não é volume zerado**. No
     * Android 9+ o mínimo do stream de alarme é 1, e um degrau acima do zero ainda toca — quem
     * calasse a barra ali trocaria a mentira pelo alarme falso, e a barra pararia de afirmar para
     * quem está sendo avisada. É o mesmo cuidado que o [NotificationHelper.reminderAlerts] tomou com
     * o volume (`OracleSanidadeTest.oAlarmeNoVolumeMaisBaixoMasNaoZeradoAindaEhSaudavel`), agora no
     * caminho da voz.
     */
    @Test
    fun comOAlarmeNoMinimoMasNaoZeradoABarraAindaAfirmaQueFalou() {
        volumeDoAlarme(1)

        falarComOMotorDeVerdade()

        assertThat(afirmaQueEstaFalando()).isTrue()
    }
}

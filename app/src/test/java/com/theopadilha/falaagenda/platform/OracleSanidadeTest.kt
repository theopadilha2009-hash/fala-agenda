package com.theopadilha.falaagenda.platform

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.reminders.NotificationHelper
import com.theopadilha.falaagenda.reminders.NotificationHelper.ReminderAlerts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O oráculo do que o aplicativo afirma sobre os próprios avisos.
 *
 * O invariante é uma frase que se pode escrever sem ter visto o código: **se o aplicativo diz
 * que os avisos estão valendo, o aparelho tem que deixar soar.** O que se mede aqui não é o
 * texto do cartão nem a cor dele — é a mentira: o aplicativo declarar saudável um aparelho que
 * não vai tocar.
 *
 * A queixa literal da dona do aparelho é "o áudio nunca funciona", e ela é surda ao motivo: um
 * lembrete que sai mudo e um aplicativo que diz "avisos OK" são, para ela, a mesma experiência.
 *
 * O esperado **não** sai da regra do código. Cada estado é montado pelo teste com um volume e
 * um modo de toque escolhidos à mão, e o "o aparelho deixa soar?" é o fato do setup — não uma
 * segunda cópia do `reminderAlerts`. É essa independência que faz o oráculo pegar a regressão
 * em vez de confirmar a implementação.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
    shadows = [ShadowAudioManagerComMinimoDeAlarme::class],
)
class OracleSanidadeTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val gerente: NotificationManager =
        base.getSystemService(NotificationManager::class.java)
    private val audio: AudioManager = base.getSystemService(AudioManager::class.java)

    private fun liberarNotificacoes() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(gerente).setNotificationsEnabled(true)
    }

    /**
     * Um canal que, sozinho, não atrapalha: alto, com som de alarme e vibrando. Todo o eixo do
     * aparelho é medido sobre ele, para que a única variável seja o aparelho.
     *
     * O canal é criado UMA vez por método de propósito: o Robolectric não sobrescreve a
     * importância de um canal já criado, então montar o produto cartesiano dentro de um método
     * só deixaria a segunda criação sem efeito — falso verde.
     */
    private fun canalSaudavel() {
        val somDeAlarme = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioDeAlarme = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        gerente.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.CHANNEL_ID,
                "Lembretes",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(somDeAlarme, audioDeAlarme)
                enableVibration(true)
            },
        )
    }

    private fun volumeDoAlarme(valor: Int) {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, valor, 0)
    }

    private fun tetoDoAlarme(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

    // --- O eixo do aparelho: modo de toque × volume do alarme ---------------------------------

    /**
     * O defeito: o aparelho pode estar sem deixar soar e o aplicativo continua dizendo "avisos
     * OK". O eixo cruza o modo de toque com o volume do alarme, e conta quantos estados o
     * aplicativo declara saudáveis sem que o aparelho vá tocar.
     *
     * A resposta certa é zero mentiras. Antes do fix são três — todos os estados com o volume do
     * alarme zerado —, e o modo de toque não muda nada porque o veredito nem olha para ele.
     */
    @Test
    fun oVereditoIgnoraOVolumeDeAlarmeDoAparelho() {
        liberarNotificacoes()
        canalSaudavel()
        val cheio = tetoDoAlarme()

        val modosDeToque = listOf(
            "toque normal" to AudioManager.RINGER_MODE_NORMAL,
            "vibrando" to AudioManager.RINGER_MODE_VIBRATE,
            "silencioso" to AudioManager.RINGER_MODE_SILENT,
        )
        val volumes = listOf("volume zero" to 0, "volume cheio" to cheio)

        val mentiras = mutableListOf<String>()
        modosDeToque.forEach { (nomeDoModo, modo) ->
            audio.ringerMode = modo
            volumes.forEach { (nomeDoVolume, volume) ->
                volumeDoAlarme(volume)
                // O fato do aparelho, sabido por construção: zerado, não sai som de jeito nenhum.
                val aparelhoDeixaSoar = volume > 0
                val veredito = NotificationHelper.reminderAlerts(base)
                if (veredito == ReminderAlerts.OK && !aparelhoDeixaSoar) {
                    mentiras += "$nomeDoModo com $nomeDoVolume: o aplicativo diz OK e o aparelho não toca"
                }
            }
        }

        assertThat(mentiras).isEmpty()
    }

    // --- Um método por estado, para o shadow não contaminar ------------------------------------

    @Test
    fun volumeDeAlarmeZeradoComToqueNormalNaoEhSaudavel() {
        liberarNotificacoes()
        canalSaudavel()
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        volumeDoAlarme(0)

        assertThat(NotificationHelper.reminderAlerts(base)).isNotEqualTo(ReminderAlerts.OK)
    }

    @Test
    fun volumeDeAlarmeZeradoComOVibraNaoEhSaudavel() {
        liberarNotificacoes()
        canalSaudavel()
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        volumeDoAlarme(0)

        assertThat(NotificationHelper.reminderAlerts(base)).isNotEqualTo(ReminderAlerts.OK)
    }

    @Test
    fun volumeDeAlarmeZeradoComOCelularSilenciosoNaoEhSaudavel() {
        liberarNotificacoes()
        canalSaudavel()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        volumeDoAlarme(0)

        assertThat(NotificationHelper.reminderAlerts(base)).isNotEqualTo(ReminderAlerts.OK)
    }

    /**
     * O limite honesto do critério, travado de propósito: **o modo silencioso sozinho não vira
     * cartão**. O canal do lembrete é de ALARME (`USAGE_ALARM`), e o modo silencioso governa o
     * toque, não o alarme — quem liga o silencioso à noite espera que o despertador ainda toque.
     * Tratar silencioso como mudo seria o falso positivo que ensina a ignorar o cartão.
     */
    @Test
    fun oCelularNoSilenciosoComOAlarmeNoVolumeAindaEhSaudavel() {
        liberarNotificacoes()
        canalSaudavel()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        volumeDoAlarme(tetoDoAlarme())

        assertThat(NotificationHelper.reminderAlerts(base)).isEqualTo(ReminderAlerts.OK)
    }

    /**
     * O outro limite: **volume baixo não é volume zerado**. Um alarme no degrau mais baixo ainda
     * toca, e acender o cartão ali gastaria o aviso para quem continua sendo avisada. É o mesmo
     * cuidado que o cartão "sem som" já tinha com o canal que ainda vibra.
     */
    @Test
    fun oAlarmeNoVolumeMaisBaixoMasNaoZeradoAindaEhSaudavel() {
        liberarNotificacoes()
        canalSaudavel()
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        volumeDoAlarme(1)

        assertThat(NotificationHelper.reminderAlerts(base)).isEqualTo(ReminderAlerts.OK)
    }

    // --- Controle de sanidade: a medição consegue cair? ----------------------------------------

    /**
     * O extremo saudável. Se o oráculo fosse sempre "não saudável", é este que cairia.
     */
    @Test
    fun canalComSomEAlarmeAudivelEhSaudavel() {
        liberarNotificacoes()
        canalSaudavel()
        volumeDoAlarme(tetoDoAlarme())

        assertThat(NotificationHelper.reminderAlerts(base)).isEqualTo(ReminderAlerts.OK)
    }

    /**
     * O extremo doente. Se o oráculo fosse sempre verde — o defeito que este arquivo existe para
     * pegar —, é este que cairia. Os dois juntos provam que a medição distingue os dois mundos.
     */
    @Test
    fun canalSemSomEAlarmeAudivelNaoEhSaudavel() {
        liberarNotificacoes()
        gerente.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.CHANNEL_ID,
                "Lembretes",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) },
        )
        volumeDoAlarme(tetoDoAlarme())

        assertThat(NotificationHelper.reminderAlerts(base)).isNotEqualTo(ReminderAlerts.OK)
    }
}

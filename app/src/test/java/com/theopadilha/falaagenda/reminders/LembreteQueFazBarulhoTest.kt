package com.theopadilha.falaagenda.reminders

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O lembrete de remédio tem que ser um ALARME, não um aviso de mensagem.
 *
 * Medido no aparelho: a notificação saía sem som próprio, sem vibração própria, sem categoria de
 * alarme e sem "não sai no swipe" — o único áudio era o plim curto do som padrão de notificação do
 * canal, que toca uma vez e para. Um lembrete das 08:00, na cozinha, com o celular na sala, não é
 * um alarme: é um plim que ninguém ouve. E o canal de `IMPORTANCE_HIGH` com o som posto em
 * "Nenhum" passava como saudável — o cartão "sem som" da home não aparecia e o lembrete saía mudo
 * com o app achando que tinha avisado.
 *
 * Este arquivo trava os três: o canal com som de alarme (e a migração do canal antigo, que não
 * muda de som depois de criado), o lembrete que não sai no swipe acidental, e a sondagem que para
 * de mentir quando o canal está alto mas mudo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class LembreteQueFazBarulhoTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val gerente: NotificationManager =
        contexto.getSystemService(NotificationManager::class.java)
    private val ocorrencia = "remedio:2026-10-06"

    /**
     * O id do canal da versão anterior, congelado aqui como string de propósito: ele é um fato
     * histórico do aparelho dela, e não uma constante que pode mudar de valor junto com o código.
     * É este id que está gravado no celular, mudo, e é ele que a migração precisa apagar.
     */
    private val idDoCanalAntigo = "fala_agenda_reminders"

    private fun liberarNotificacoes() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(gerente).setNotificationsEnabled(true)
    }

    /**
     * O eixo do aparelho fora do caminho: o Robolectric nasce com o volume de alarme zerado, e
     * sem isto o veredito seria [NotificationHelper.ReminderAlerts.APARELHO_MUDO] — que é um
     * defeito do aparelho, e não o do canal que estes casos medem. O aparelho audível é o
     * pressuposto deles, agora explícito.
     */
    private fun aparelhoAudivel() {
        val audio = contexto.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
    }

    private fun criarCanalMudo(importancia: Int) {
        gerente.createNotificationChannel(
            NotificationChannel(NotificationHelper.CHANNEL_ID, "Lembretes", importancia)
                .apply { setSound(null, null) },
        )
    }

    private fun notificacaoDoLembrete(): Notification =
        shadowOf(gerente).getNotification(AlarmIds.requestCode(ocorrencia, AlarmIds.NOTIF_REMINDER))

    // --- 1. O som de alarme, não o plim da notificação ---------------------------------------

    /**
     * O canal do lembrete tem que subir no volume de ALARME. Um canal sem `setSound` toca o som
     * padrão de notificação — o plim curto de qualquer mensagem — e o `AudioAttributes` de
     * notificação é justamente o que faz o sistema respeitar o volume de notificação, que costuma
     * estar baixo ou mudo. O `USAGE_ALARM` é o que põe o lembrete no volume do despertador.
     */
    @Test
    fun canalDoLembreteTocaNoVolumeDeAlarme() {
        NotificationHelper.ensureChannel(contexto)

        val canal = gerente.getNotificationChannel(NotificationHelper.CHANNEL_ID)!!
        assertThat(canal.sound).isNotNull()
        assertThat(canal.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
    }

    /**
     * O canal do aparelho dela já existe, e **um canal criado não muda de som**: o sistema ignora
     * um `createNotificationChannel` com o mesmo id. Sem apagar o antigo, o fix só valeria em
     * instalação limpa — e o celular dela, que é o que importa, continuaria com o plim.
     */
    @Test
    fun aparelhoComOCanalAntigoGanhaOCanalComSomDeAlarme() {
        gerente.createNotificationChannel(
            NotificationChannel(idDoCanalAntigo, "Lembretes", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )

        NotificationHelper.ensureChannel(contexto)

        assertThat(gerente.getNotificationChannel(idDoCanalAntigo)).isNull()
        val canalNovo = gerente.getNotificationChannel(NotificationHelper.CHANNEL_ID)!!
        assertThat(canalNovo.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
    }

    /** Chamar de novo não pode apagar nem recriar o canal que acabou de nascer certo. */
    @Test
    fun criarOCanalDeNovoNaoDesfazOCanalComSom() {
        NotificationHelper.ensureChannel(contexto)
        NotificationHelper.ensureChannel(contexto)

        val canal = gerente.getNotificationChannel(NotificationHelper.CHANNEL_ID)!!
        assertThat(canal.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(canal.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }

    /** O lembrete sai no canal com som — e não no antigo, que segue mudo no aparelho dela. */
    @Test
    fun oLembreteSaiNoCanalComSomDeAlarme() {
        liberarNotificacoes()

        NotificationHelper.showReminder(contexto, ocorrencia, "remedio", "Remédio")

        val notificacao = notificacaoDoLembrete()
        assertThat(notificacao.channelId).isEqualTo(NotificationHelper.CHANNEL_ID)
        assertThat(gerente.getNotificationChannel(notificacao.channelId).audioAttributes.usage)
            .isEqualTo(AudioAttributes.USAGE_ALARM)
    }

    // --- 2. O aviso que não sai no swipe acidental -------------------------------------------

    /**
     * Um aviso de remédio que ela apaga sem ler, sem querer, morre calado. Categoria de alarme e
     * "não sai no swipe" é o que impede isso; os botões "Concluir" e "Adiar 30 min" da própria
     * notificação continuam sendo o caminho dela para dispensar.
     */
    @Test
    fun oLembreteNaoSaiDaBarraComOSwipeAcidental() {
        liberarNotificacoes()

        NotificationHelper.showReminder(contexto, ocorrencia, "remedio", "Remédio")

        val notificacao = notificacaoDoLembrete()
        assertThat(notificacao.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notificacao.category).isEqualTo(Notification.CATEGORY_ALARM)
    }

    /**
     * O caminho dela de dispensar continua de pé: os dois botões seguem na notificação, com o
     * título que ela já conhece. "Não sai no swipe" sem os botões seria uma notificação presa.
     */
    @Test
    fun concluirEAdiarContinuamNaNotificacao() {
        liberarNotificacoes()

        NotificationHelper.showReminder(contexto, ocorrencia, "remedio", "Remédio")

        val acoes = notificacaoDoLembrete().actions ?: emptyArray()
        assertThat(acoes.map { it.title.toString() })
            .containsExactly(
                contexto.getString(R.string.complete),
                contexto.getString(R.string.snooze_30),
            )
    }

    // --- 3. A sondagem que para de mentir ------------------------------------------------------

    /**
     * Canal alto com o som em "Nenhum": o app dizia "avisos OK", o cartão "sem som" não aparecia,
     * e o lembrete saía mudo com o aplicativo achando que tinha avisado. É o defeito que este
     * arquivo trava.
     */
    @Test
    fun canalAltoComOSomDesligadoEhSemSom() {
        liberarNotificacoes()
        criarCanalMudo(NotificationManager.IMPORTANCE_HIGH)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.QUIET)
        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isTrue()
    }

    /**
     * Som desligado mas vibração ligada **não** conta como mudo, e é deliberado: o canal ainda
     * chama a atenção dela, e o cartão "sem som" oferece abrir os Ajustes do canal — que é onde o
     * toque vai parar mesmo assim. Tratar este caso como mudo trocaria um aviso verdadeiro por um
     * alarme falso, e o cartão apareceria para quem ainda é avisada.
     */
    @Test
    fun canalAltoComOVibraLigadoNaoEhTratadoComoSemSom() {
        liberarNotificacoes()
        aparelhoAudivel()
        gerente.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.CHANNEL_ID, "Lembretes", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(true)
            },
        )

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.OK)
    }

    /** Canal alto, com som de verdade: aí sim os avisos estão valendo. */
    @Test
    fun canalAltoComSomEhAvisoQueFunciona() {
        liberarNotificacoes()
        aparelhoAudivel()
        NotificationHelper.ensureChannel(contexto)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.OK)
    }

    /**
     * Antes do primeiro agendamento não existe canal nenhum. Isso não é "mudo" — é "ainda não
     * criado", e inventar um aviso falso para ela seria o mesmo defeito na direção oposta.
     */
    @Test
    fun canalAindaNaoCriadoNaoEhSemSom() {
        liberarNotificacoes()
        assertThat(gerente.getNotificationChannel(NotificationHelper.CHANNEL_ID)).isNull()

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.OK)
    }

    /** Canal desligado continua sendo "nada aparece", e não "sem som" — quem não sai não é entrega. */
    @Test
    fun canalDesligadoContinuaSendoAvisosDesligados() {
        liberarNotificacoes()
        gerente.createNotificationChannel(
            NotificationChannel(
                NotificationHelper.CHANNEL_ID, "Lembretes", NotificationManager.IMPORTANCE_NONE,
            ),
        )

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.OFF)
    }
}

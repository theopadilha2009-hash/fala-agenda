package com.theopadilha.falaagenda.platform

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.reminders.ActionResponse
import com.theopadilha.falaagenda.reminders.AlarmIds
import com.theopadilha.falaagenda.reminders.NotificationHelper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cobre `reminders.NotificationHelper`, que decide se o lembrete de quem usa o app sai ou não
 * e não tinha teste nenhum. O arquivo mora neste pacote porque o contrato da tarefa só permitia
 * arquivo novo sob `.../platform/`; o que ele exercita é o ajudante de lembrete.
 *
 * A suíte unitária roda com `isIncludeAndroidResources = false`, então nenhum recurso do app
 * está mesclado e `getString` estoura. Um `ContextWrapper` devolve texto fixo no lugar disso;
 * nada do que se afirma aqui depende do texto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class NotificationHelperTest {
    private val contexto: Context = SemTextos(ApplicationProvider.getApplicationContext())
    private val gerente: NotificationManager =
        contexto.getSystemService(NotificationManager::class.java)
    private val ocorrencia = "s1:2026-09-27"

    private fun liberarNotificacoes() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(gerente).setNotificationsEnabled(true)
    }

    /**
     * Deixa o eixo do APARELHO fora do caminho: com o volume de alarme zerado — o default do
     * Robolectric — o veredito é [NotificationHelper.ReminderAlerts.APARELHO_MUDO] e os testes
     * daqui, que medem o CANAL, parariam de dizer o que querem dizer. O aparelho audível é o
     * pressuposto destes casos, e explicitá-lo é o que separa "o canal está certo" de "o som
     * sai" — que é a pergunta do `OracleSanidadeTest`.
     */
    private fun aparelhoAudivel() {
        val audio = contexto.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
    }

    @Test
    fun canalDoLembreteNasceComIdEImportanciaCertos() {
        NotificationHelper.ensureChannel(contexto)

        val canal = gerente.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        assertThat(canal).isNotNull()
        assertThat(canal!!.id).isEqualTo(NotificationHelper.CHANNEL_ID)
        assertThat(canal.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(canal.shouldVibrate()).isTrue()
    }

    @Test
    fun lembreteSaiComOIdDoAlarme() {
        liberarNotificacoes()

        val entrega = NotificationHelper.showReminder(contexto, ocorrencia, "s1", "Vitamina")

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.POSTED)
        assertThat(shadowOf(gerente).size()).isEqualTo(1)
        assertThat(shadowOf(gerente).getNotification(AlarmIds.requestCode(ocorrencia, "notif")))
            .isNotNull()
    }

    @Test
    fun semPermissaoDeNotificacaoNadaEhPostado() {
        liberarNotificacoes()
        shadowOf(gerente).setNotificationsEnabled(false)

        val entrega = NotificationHelper.showReminder(contexto, ocorrencia, "s1", "Vitamina")

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.BLOCKED)
        assertThat(shadowOf(gerente).size()).isEqualTo(0)
    }

    @Test
    fun cancelTiraOlembreteQueEstavaNaTela() {
        liberarNotificacoes()
        NotificationHelper.showReminder(contexto, ocorrencia, "s1", "Vitamina")
        assertThat(shadowOf(gerente).size()).isEqualTo(1)

        NotificationHelper.cancel(contexto, ocorrencia)

        assertThat(shadowOf(gerente).size()).isEqualTo(0)
    }

    @Test
    fun canalRebaixadoEhLidoComoSilencioso() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_LOW)

        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isTrue()
    }

    @Test
    fun canalAltoNaoEhSilencioso() {
        liberarNotificacoes()
        aparelhoAudivel()
        criarCanal(NotificationManager.IMPORTANCE_HIGH)

        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isFalse()
    }

    @Test
    fun semCanalCriadoNaoEhSilencioso() {
        liberarNotificacoes()

        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isFalse()
    }

    @Test
    fun semPermissaoOsAvisosEstaoDesligados() {
        liberarNotificacoes()
        shadowOf(gerente).setNotificationsEnabled(false)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.OFF)
    }

    /**
     * Canal DESLIGADO não é canal quieto: com `IMPORTANCE_NONE` a notificação não é exibida de
     * forma nenhuma. Contado como "sem som", o botão do próprio cartão de avisos abria os
     * Ajustes do canal, ela desligava o canal em vez de só baixar o som, e daí em diante o app
     * dizia "sem som" enquanto o remédio das 08:00 nunca mais aparecia.
     */
    @Test
    fun canalDesligadoDeixaOsAvisosDesligados() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_NONE)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.OFF)
    }

    /**
     * O canal desligado também não pode devolver `POSTED`: quem lê esse desfecho gasta o degrau
     * da escada e marca a ocorrência como avisada. Ela nunca viu nada, e o aviso seguinte —
     * que o sistema também descartaria — deixava de ser armado.
     */
    @Test
    fun canalDesligadoNaoPostaOLembrete() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_NONE)

        val entrega = NotificationHelper.showReminder(contexto, ocorrencia, "s1", "Vitamina")

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.BLOCKED)
        assertThat(shadowOf(gerente).size()).isEqualTo(0)
    }

    /**
     * O canal rebaixado (mas ligado) continua sendo entrega: a notificação aparece, só não faz
     * barulho — e é o cartão "sem som" que cuida disso. Desligado é que não é entrega.
     */
    @Test
    fun canalRebaixadoContinuaPostandoOLembrete() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_LOW)

        val entrega = NotificationHelper.showReminder(contexto, ocorrencia, "s1", "Vitamina")

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.POSTED)
        assertThat(shadowOf(gerente).size()).isEqualTo(1)
    }

    @Test
    fun canalRebaixadoDeixaOsAvisosSemSom() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_LOW)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.QUIET)
    }

    @Test
    fun canalAltoComPermissaoEhAvisoQueFunciona() {
        liberarNotificacoes()
        aparelhoAudivel()
        criarCanal(NotificationManager.IMPORTANCE_HIGH)

        assertThat(NotificationHelper.reminderAlerts(contexto))
            .isEqualTo(NotificationHelper.ReminderAlerts.OK)
    }


    @Test
    fun notificacaoBloqueadaEhSilenciosa() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_HIGH)
        shadowOf(gerente).setNotificationsEnabled(false)

        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isTrue()
    }

    @Test
    fun avisoDeAcaoNaoAplicadaNaoTomaOIdDoLembrete() {
        liberarNotificacoes()
        NotificationHelper.showReminder(contexto, ocorrencia, "s1", "Vitamina")

        val entrega = NotificationHelper.showActionNotApplied(
            contexto,
            ocorrencia,
            AlarmIds.ACTION_SNOOZE,
            ActionResponse.GONE,
        )

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.POSTED)
        assertThat(shadowOf(gerente).size()).isEqualTo(2)
        assertThat(shadowOf(gerente).getNotification(idDoLembrete())).isNotNull()
        assertThat(shadowOf(gerente).getNotification(idDoAviso())).isNotNull()
    }

    @Test
    fun avisoDeAcaoNaoAplicadaSaiNoCanalDoLembrete() {
        liberarNotificacoes()

        NotificationHelper.showActionNotApplied(
            contexto,
            ocorrencia,
            AlarmIds.ACTION_SNOOZE,
            ActionResponse.GONE,
        )

        assertThat(shadowOf(gerente).getNotification(idDoAviso())!!.channelId)
            .isEqualTo(NotificationHelper.CHANNEL_ID)
    }

    @Test
    fun semPermissaoOAvisoDeAcaoNaoAplicadaNaoEhPublicado() {
        liberarNotificacoes()
        shadowOf(gerente).setNotificationsEnabled(false)

        val entrega = NotificationHelper.showActionNotApplied(
            contexto,
            ocorrencia,
            AlarmIds.ACTION_SNOOZE,
            ActionResponse.GONE,
        )

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.BLOCKED)
        assertThat(shadowOf(gerente).size()).isEqualTo(0)
    }

    /**
     * O aviso de ação não aplicada sai pelo MESMO canal do lembrete, e o canal desligado o
     * descartava calado: `areNotificationsEnabled()` continua verdadeiro, o `notify` não lança,
     * e a notificação some antes de ela ver. Aqui isso pesa mais que no lembrete — o aviso
     * existe só para dizer que o "Adiar" dela não pegou; descartado, ela fica esperando um
     * aviso que ninguém agendou, sem saber de nada. Mesmo guard: canal desligado não é entrega.
     */
    @Test
    fun canalDesligadoNaoPostaOAvisoDeAcao() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_NONE)

        val entrega = NotificationHelper.showActionNotApplied(
            contexto,
            ocorrencia,
            AlarmIds.ACTION_SNOOZE,
            ActionResponse.GONE,
        )

        assertThat(entrega).isEqualTo(NotificationHelper.ReminderDelivery.BLOCKED)
        assertThat(shadowOf(gerente).size()).isEqualTo(0)
    }

    @Test
    fun cadaAcaoQuePodeFalharTemTituloETextoProprios() {
        val acoes = listOf(AlarmIds.ACTION_COMPLETE, AlarmIds.ACTION_SNOOZE, "acao-sem-texto-proprio")
        val motivos = listOf(ActionResponse.GONE, ActionResponse.UNFINISHED)

        assertThat(acoes.map { NotificationHelper.actionNotAppliedTitle(it) }.toSet()).hasSize(acoes.size)
        motivos.forEach { motivo ->
            assertThat(acoes.map { NotificationHelper.actionNotAppliedText(it, motivo) }.toSet())
                .hasSize(acoes.size)
        }
    }

    /**
     * O texto não pode afirmar a causa errada. "Esta tarefa não está mais na agenda" é verdade no
     * [ActionResponse.GONE] e chute no [ActionResponse.UNFINISHED], em que o trabalho estourou o
     * tempo ou caiu e ninguém sabe o que foi gravado. Reaproveitar o texto do GONE ali seria dizer
     * a ela uma causa que o aplicativo não verificou.
     */
    @Test
    fun oTrabalhoQueNaoTerminouNaoUsaOTextoDoQueSaiuDaAgenda() {
        listOf(AlarmIds.ACTION_COMPLETE, AlarmIds.ACTION_SNOOZE, "acao-sem-texto-proprio").forEach { acao ->
            assertThat(NotificationHelper.actionNotAppliedText(acao, ActionResponse.UNFINISHED))
                .isNotEqualTo(NotificationHelper.actionNotAppliedText(acao, ActionResponse.GONE))
        }
    }

    @Test
    fun oAvisoPublicadoUsaOTextoEscolhidoParaAAcao() {
        liberarNotificacoes()

        NotificationHelper.showActionNotApplied(
            contexto,
            ocorrencia,
            AlarmIds.ACTION_SNOOZE,
            ActionResponse.GONE,
        )
        val doAdiar = textoDoAviso()

        NotificationHelper.showActionNotApplied(
            contexto,
            ocorrencia,
            AlarmIds.ACTION_COMPLETE,
            ActionResponse.GONE,
        )
        val doConcluir = textoDoAviso()

        assertThat(doAdiar).isEqualTo(
            contexto.getString(
                NotificationHelper.actionNotAppliedText(AlarmIds.ACTION_SNOOZE, ActionResponse.GONE),
            ),
        )
        assertThat(doAdiar).isNotEqualTo(doConcluir)
    }

    /**
     * O motivo tem que chegar na notificação, e não só na função de texto: o aviso do trabalho que
     * não terminou é o único que sai quando o toque dela valeu ou não — repetir ali o "não está mais
     * na agenda" seria anunciar uma causa inventada justamente no caso em que nada se sabe.
     */
    @Test
    fun oAvisoPublicadoUsaOTextoDoMotivoDoTrabalhoQueNaoTerminou() {
        liberarNotificacoes()

        NotificationHelper.showActionNotApplied(
            contexto,
            ocorrencia,
            AlarmIds.ACTION_SNOOZE,
            ActionResponse.UNFINISHED,
        )

        assertThat(textoDoAviso()).isEqualTo(
            contexto.getString(
                NotificationHelper.actionNotAppliedText(
                    AlarmIds.ACTION_SNOOZE,
                    ActionResponse.UNFINISHED,
                ),
            ),
        )
    }

    private fun idDoLembrete() = AlarmIds.requestCode(ocorrencia, AlarmIds.NOTIF_REMINDER)

    private fun idDoAviso() = AlarmIds.requestCode(ocorrencia, AlarmIds.NOTIF_NOT_APPLIED)

    private fun textoDoAviso(): String =
        shadowOf(gerente).getNotification(idDoAviso()).extras.getString(Notification.EXTRA_TEXT)!!

    private fun criarCanal(importancia: Int) {
        gerente.createNotificationChannel(
            NotificationChannel(NotificationHelper.CHANNEL_ID, "Lembretes", importancia),
        )
    }

    private class SemTextos(base: Context) : ContextWrapper(base) {
        override fun getResources(): Resources = RecursosSemTextos(super.getResources())
    }

    private class RecursosSemTextos(base: Resources) :
        Resources(base.assets, base.displayMetrics, base.configuration) {

        override fun getString(id: Int): String = TEXTO + id

        override fun getString(id: Int, vararg formatArgs: Any?): String = TEXTO + id

        override fun getText(id: Int): CharSequence = TEXTO + id
    }

    private companion object {
        const val TEXTO = "texto"
    }
}

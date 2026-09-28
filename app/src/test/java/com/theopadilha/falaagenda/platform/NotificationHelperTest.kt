package com.theopadilha.falaagenda.platform

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
        criarCanal(NotificationManager.IMPORTANCE_HIGH)

        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isFalse()
    }

    @Test
    fun semCanalCriadoNaoEhSilencioso() {
        liberarNotificacoes()

        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isFalse()
    }

    @Test
    fun notificacaoBloqueadaEhSilenciosa() {
        liberarNotificacoes()
        criarCanal(NotificationManager.IMPORTANCE_HIGH)
        shadowOf(gerente).setNotificationsEnabled(false)

        assertThat(NotificationHelper.remindersWillBeSilent(contexto)).isTrue()
    }

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

        override fun getString(id: Int): String = TEXTO

        override fun getString(id: Int, vararg formatArgs: Any?): String = TEXTO

        override fun getText(id: Int): CharSequence = TEXTO
    }

    private companion object {
        const val TEXTO = "texto"
    }
}

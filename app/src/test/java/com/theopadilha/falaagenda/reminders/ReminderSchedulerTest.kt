package com.theopadilha.falaagenda.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reagendar tem que cancelar só o alarme. O aviso que já está na barra sem ser visto
 * não pode desaparecer porque o app reabriu, reiniciou ou virou o dia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class ReminderSchedulerTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val scheduler = ReminderScheduler(context, SettingsStore(context))

    private val occurrenceId = "s1:2026-09-27"
    private val notificationId = AlarmIds.requestCode(occurrenceId, "notif")

    @Test
    fun reagendarPreservaNotificacaoJaPublicada() {
        publicarNotificacao()

        val outcome = scheduler.schedule(occurrence(), series(), first = false)

        assertThat(outcome.scheduled).isTrue()
        assertThat(shadowOf(notificationManager).getNotification(notificationId)).isNotNull()
    }

    @Test
    fun cancelarOcorrenciaRemoveNotificacao() {
        publicarNotificacao()

        scheduler.cancel(occurrenceId)

        assertThat(shadowOf(notificationManager).getNotification(notificationId)).isNull()
    }

    private fun publicarNotificacao() {
        notificationManager.notify(
            notificationId,
            NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Remédio")
                .build(),
        )
    }

    private fun occurrence() = TaskOccurrence(
        id = occurrenceId,
        seriesId = "s1",
        localDate = LocalDate.of(2026, 9, 27),
        scheduledAt = Instant.parse("2026-09-27T11:00:00Z"),
        status = OccurrenceStatus.PENDING,
        nextReminderAt = Instant.parse("2026-09-27T11:30:00Z"),
    )

    private fun series() = TaskSeries(
        id = "s1",
        title = "Remédio",
        zoneId = zone,
        localTime = LocalTime.of(8, 0),
        startLocalDate = LocalDate.of(2026, 9, 27),
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        createdAt = Instant.parse("2026-09-27T10:00:00Z"),
        updatedAt = Instant.parse("2026-09-27T10:00:00Z"),
    )
}

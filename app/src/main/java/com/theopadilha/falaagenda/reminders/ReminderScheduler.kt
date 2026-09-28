package com.theopadilha.falaagenda.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.data.repo.SchedulerOutcome
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import java.time.Instant

class ReminderScheduler(
    private val context: Context,
    private val settings: SettingsStore,
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager,
) : AlarmScheduler {
    override suspend fun quietHours(): QuietHours = settings.currentQuietHours()

    override fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    override fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean): SchedulerOutcome {
        val fireAt = occurrence.nextReminderAt ?: return SchedulerOutcome(inexact = false, scheduled = false)
        // Só o alarme: a notificação publicada pode estar na barra sem o usuário ter
        // visto, e reagendar acontece em todo start, boot e virada do dia.
        cancelAlarm(occurrence.id)
        val pi = firePendingIntent(occurrence.id, series)
        val showIntent = PendingIntent.getActivity(
            context,
            AlarmIds.requestCode(occurrence.id, AlarmIds.ACTION_OPEN),
            AlarmIds.openIntent(context, occurrence.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = fireAt.toEpochMilli()
        val exact = canScheduleExact()
        if (exact) {
            if (first) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(millis, showIntent), pi)
            } else {
                setExactCompat(millis, pi)
            }
        } else {
            setInexactCompat(millis, pi)
        }
        return SchedulerOutcome(inexact = !exact, scheduled = true)
    }

    override fun scheduleRecovery(occurrenceId: String, at: Instant) {
        val pi = firePendingIntent(occurrenceId, series = null)
        val millis = at.toEpochMilli()
        // Mesmo com alvo exato, recuperação é reagendamento de um disparo já perdido:
        // não é "primeiro lembrete" e não vale acordar a tela do aparelho.
        if (canScheduleExact()) {
            setExactCompat(millis, pi)
        } else {
            setInexactCompat(millis, pi)
        }
    }

    override fun cancel(occurrenceId: String) {
        cancelAlarm(occurrenceId)
        listOf(AlarmIds.ACTION_COMPLETE, AlarmIds.ACTION_SNOOZE).forEach { action ->
            val intent = Intent(context, ReminderActionReceiver::class.java).setAction(action)
            val pi = PendingIntent.getBroadcast(
                context,
                AlarmIds.requestCode(occurrenceId, action),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                alarmManager.cancel(pi)
                pi.cancel()
            }
        }
        NotificationHelper.cancel(context, occurrenceId)
    }

    private fun cancelAlarm(occurrenceId: String) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(AlarmIds.ACTION_FIRE)
        val pi = PendingIntent.getBroadcast(
            context,
            AlarmIds.requestCode(occurrenceId, AlarmIds.ACTION_FIRE),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun firePendingIntent(occurrenceId: String, series: TaskSeries?): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = AlarmIds.ACTION_FIRE
            putExtra(AlarmIds.EXTRA_OCCURRENCE_ID, occurrenceId)
            if (series != null) {
                putExtra(AlarmIds.EXTRA_SERIES_ID, series.id)
                putExtra("title", series.title)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            AlarmIds.requestCode(occurrenceId, AlarmIds.ACTION_FIRE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setExactCompat(millis: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun setInexactCompat(millis: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }
}

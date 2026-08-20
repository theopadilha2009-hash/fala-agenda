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
        cancel(occurrence.id)
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = AlarmIds.ACTION_FIRE
            putExtra(AlarmIds.EXTRA_OCCURRENCE_ID, occurrence.id)
            putExtra(AlarmIds.EXTRA_SERIES_ID, series.id)
            putExtra("title", series.title)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            AlarmIds.requestCode(occurrence.id, AlarmIds.ACTION_FIRE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showIntent = PendingIntent.getActivity(
            context,
            AlarmIds.requestCode(occurrence.id, "open"),
            Intent(context, com.theopadilha.falaagenda.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AlarmIds.EXTRA_OCCURRENCE_ID, occurrence.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val millis = fireAt.toEpochMilli()
        val exact = canScheduleExact()
        if (exact) {
            if (first) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(millis, showIntent), pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, millis, pi)
            }
        }
        return SchedulerOutcome(inexact = !exact, scheduled = true)
    }

    override fun cancel(occurrenceId: String) {
        listOf(AlarmIds.ACTION_FIRE, AlarmIds.ACTION_COMPLETE, AlarmIds.ACTION_SNOOZE).forEach { action ->
            val clazz = if (action == AlarmIds.ACTION_FIRE) {
                ReminderAlarmReceiver::class.java
            } else {
                ReminderActionReceiver::class.java
            }
            val intent = Intent(context, clazz).setAction(action)
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
}

package com.theopadilha.falaagenda.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.theopadilha.falaagenda.FalaAgendaApplication
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "FalaAgendaReceiver"

// Acima disso o sistema considera o receiver travado e mata o processo com o trabalho pendente.
private const val WORK_TIMEOUT_MS = 8_000L

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext as FalaAgendaApplication
        app.appScope.launch {
            try {
                withTimeout(WORK_TIMEOUT_MS) {
                    val result = app.container.tasks.onAlarmFired(occurrenceId)
                    if (result.notify) {
                        val delivery = NotificationHelper.showReminder(
                            context,
                            occurrenceId,
                            result.seriesId,
                            result.title,
                        )
                        if (delivery != NotificationHelper.ReminderDelivery.POSTED) {
                            Log.w(TAG, "Lembrete $occurrenceId não apareceu: $delivery")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Tempo esgotado ao tratar o lembrete $occurrenceId")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao tratar o lembrete $occurrenceId", e)
            } finally {
                pending.finish()
            }
        }
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext as FalaAgendaApplication
        app.appScope.launch {
            try {
                withTimeout(WORK_TIMEOUT_MS) {
                    when (intent.action) {
                        AlarmIds.ACTION_COMPLETE -> app.container.tasks.complete(occurrenceId)
                        AlarmIds.ACTION_SNOOZE -> app.container.tasks.snooze(occurrenceId, 30)
                    }
                    NotificationHelper.cancel(context, occurrenceId)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Tempo esgotado ao responder o lembrete $occurrenceId")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao responder o lembrete $occurrenceId", e)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> rescheduleAsync(context)
        }
    }
}

class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> rescheduleAsync(context)
        }
    }
}

private fun BroadcastReceiver.rescheduleAsync(context: Context) {
    val pending = goAsync()
    val app = context.applicationContext as FalaAgendaApplication
    app.appScope.launch {
        try {
            app.container.tasks.rescheduleAll()
        } finally {
            pending.finish()
        }
    }
}

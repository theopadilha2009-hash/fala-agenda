package com.theopadilha.falaagenda.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.theopadilha.falaagenda.FalaAgendaApplication
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext as FalaAgendaApplication
        app.appScope.launch {
            try {
                val result = app.container.tasks.onAlarmFired(occurrenceId)
                if (result.notify) {
                    NotificationHelper.showReminder(
                        context,
                        occurrenceId,
                        result.seriesId,
                        result.title,
                    )
                }
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
                when (intent.action) {
                    AlarmIds.ACTION_COMPLETE -> app.container.tasks.complete(occurrenceId)
                    AlarmIds.ACTION_SNOOZE -> app.container.tasks.snooze(occurrenceId, 30)
                }
                NotificationHelper.cancel(context, occurrenceId)
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

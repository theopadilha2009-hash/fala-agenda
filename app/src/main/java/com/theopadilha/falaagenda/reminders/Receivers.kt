package com.theopadilha.falaagenda.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.theopadilha.falaagenda.FalaAgendaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID) ?: return
        val seriesId = intent.getStringExtra(AlarmIds.EXTRA_SERIES_ID) ?: ""
        val title = intent.getStringExtra("title") ?: "Lembrete"
        val pending = goAsync()
        val app = context.applicationContext as FalaAgendaApplication
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationHelper.showReminder(context, occurrenceId, seriesId, title)
                app.container.tasks.onAlarmFired(occurrenceId)
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
        CoroutineScope(Dispatchers.IO).launch {
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
        rescheduleAsync(context)
    }
}

class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        rescheduleAsync(context)
    }
}

private fun BroadcastReceiver.rescheduleAsync(context: Context) {
    val pending = goAsync()
    val app = context.applicationContext as FalaAgendaApplication
    CoroutineScope(Dispatchers.IO).launch {
        try {
            app.container.tasks.rescheduleAll()
        } finally {
            pending.finish()
        }
    }
}
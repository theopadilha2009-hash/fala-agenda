package com.theopadilha.falaagenda.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.theopadilha.falaagenda.R

object NotificationHelper {
    const val CHANNEL_ID = "fala_agenda_reminders"

    private const val TAG = "NotificationHelper"

    /** Como terminou a tentativa de mostrar um lembrete. */
    enum class ReminderDelivery {
        /** A notificação foi postada. */
        POSTED,

        /** O app está sem permissão de notificação: nada foi postado. */
        BLOCKED,

        /** O sistema recusou a notificação. */
        FAILED,
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Avisos de tarefas no horário combinado"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * O próximo lembrete vai sair sem som, sem vibração e sem aparecer sobre a tela?
     * Acontece quando o app está sem permissão de notificação ou quando o canal foi
     * rebaixado nas configurações do aparelho. A resposta vem do canal gravado, não
     * da constante: o sistema ignora uma criação que tente subir a importância de volta.
     */
    fun remindersWillBeSilent(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance < NotificationManager.IMPORTANCE_DEFAULT
    }

    fun showReminder(
        context: Context,
        occurrenceId: String,
        seriesId: String,
        title: String,
    ): ReminderDelivery {
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "Lembrete $occurrenceId não emitido: notificações bloqueadas para o app")
            return ReminderDelivery.BLOCKED
        }
        val open = PendingIntent.getActivity(
            context,
            AlarmIds.requestCode(occurrenceId, AlarmIds.ACTION_OPEN),
            AlarmIds.openIntent(context, occurrenceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val complete = actionPending(context, occurrenceId, seriesId, AlarmIds.ACTION_COMPLETE)
        val snooze = actionPending(context, occurrenceId, seriesId, AlarmIds.ACTION_SNOOZE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Está na hora. Pode concluir ou adiar daqui, sem abrir o aplicativo.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, context.getString(R.string.complete), complete)
            .addAction(0, context.getString(R.string.snooze_30), snooze)
            .build()
        return try {
            NotificationManagerCompat.from(context)
                .notify(AlarmIds.requestCode(occurrenceId, "notif"), notification)
            if (remindersWillBeSilent(context)) {
                Log.w(TAG, "Lembrete $occurrenceId apareceu sem som: canal $CHANNEL_ID rebaixado")
            }
            ReminderDelivery.POSTED
        } catch (e: SecurityException) {
            Log.w(TAG, "Lembrete $occurrenceId recusado pelo sistema", e)
            ReminderDelivery.FAILED
        }
    }

    fun cancel(context: Context, occurrenceId: String) {
        NotificationManagerCompat.from(context).cancel(AlarmIds.requestCode(occurrenceId, "notif"))
    }

    private fun actionPending(
        context: Context,
        occurrenceId: String,
        seriesId: String,
        action: String,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmIds.EXTRA_OCCURRENCE_ID, occurrenceId)
            putExtra(AlarmIds.EXTRA_SERIES_ID, seriesId)
        }
        return PendingIntent.getBroadcast(
            context,
            AlarmIds.requestCode(occurrenceId, action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

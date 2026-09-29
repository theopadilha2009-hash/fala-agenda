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
        val open = openPending(context, occurrenceId)
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
                .notify(AlarmIds.requestCode(occurrenceId, AlarmIds.NOTIF_REMINDER), notification)
            if (remindersWillBeSilent(context)) {
                Log.w(TAG, "Lembrete $occurrenceId apareceu sem som: canal $CHANNEL_ID rebaixado")
            }
            ReminderDelivery.POSTED
        } catch (e: SecurityException) {
            Log.w(TAG, "Lembrete $occurrenceId recusado pelo sistema", e)
            ReminderDelivery.FAILED
        }
    }

    /**
     * Ela tocou num botão da notificação e a ação não pegou: a ocorrência saiu da agenda entre o
     * aviso e o toque. Sem isto o lembrete só desaparecia — e, no "adiar", ela ficava esperando
     * um aviso que ninguém agendou. No remédio, o remédio que não toca.
     *
     * Sai no mesmo canal do lembrete, que é onde ela já sabe procurar; um canal novo não teria
     * som nem permissão garantidos. Não leva botões: a ação que ela tocou é justamente a que
     * não pegou, e oferecê-la de novo só repetiria a falha.
     */
    fun showActionNotApplied(context: Context, occurrenceId: String, action: String) {
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "Aviso de ação não aplicada $occurrenceId não emitido: notificações bloqueadas")
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(actionNotAppliedTitle(action)))
            .setContentText(context.getString(actionNotAppliedText(action)))
            .setContentIntent(openPending(context, occurrenceId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(
                AlarmIds.requestCode(occurrenceId, AlarmIds.NOTIF_NOT_APPLIED),
                notification,
            )
            // Aqui ela precisa notar: um aviso mudo é quase tão ruim quanto nenhum.
            if (remindersWillBeSilent(context)) {
                Log.w(TAG, "Aviso de ação não aplicada $occurrenceId apareceu sem som: canal rebaixado")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Aviso de ação não aplicada $occurrenceId recusado pelo sistema", e)
        }
    }

    /**
     * O aviso muda com o que ela tentou fazer: "concluir" e "adiar" terminam em histórias
     * diferentes na cabeça dela — no "adiar" o que não pode ficar por dizer é que outro aviso não
     * vai tocar. Ação sem texto próprio cai no genérico: vago é melhor do que mudo.
     */
    fun actionNotAppliedTitle(action: String): Int = when (action) {
        AlarmIds.ACTION_COMPLETE -> R.string.action_not_applied_title_complete
        AlarmIds.ACTION_SNOOZE -> R.string.action_not_applied_title_snooze
        else -> R.string.action_not_applied_title
    }

    fun actionNotAppliedText(action: String): Int = when (action) {
        AlarmIds.ACTION_COMPLETE -> R.string.action_not_applied_text_complete
        AlarmIds.ACTION_SNOOZE -> R.string.action_not_applied_text_snooze
        else -> R.string.action_not_applied_text
    }

    fun cancel(context: Context, occurrenceId: String) {
        NotificationManagerCompat.from(context)
            .cancel(AlarmIds.requestCode(occurrenceId, AlarmIds.NOTIF_REMINDER))
    }

    private fun openPending(context: Context, occurrenceId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            AlarmIds.requestCode(occurrenceId, AlarmIds.ACTION_OPEN),
            AlarmIds.openIntent(context, occurrenceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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

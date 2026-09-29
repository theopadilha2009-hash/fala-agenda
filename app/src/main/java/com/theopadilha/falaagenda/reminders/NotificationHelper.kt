package com.theopadilha.falaagenda.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    /**
     * O que os avisos deste aplicativo estão valendo agora.
     *
     * [OFF] é "nada aparece": a permissão negada ou o canal DESLIGADO nas configurações — os
     * dois terminam em [ReminderDelivery.BLOCKED], e o degrau da escada fica sem entrega.
     * [QUIET] é o canal rebaixado: a notificação sai, mas muda, e para quem depende dela ser
     * lembrada isso é quase o mesmo que não sair.
     */
    enum class ReminderAlerts { OK, OFF, QUIET }

    fun ensureChannel(context: Context) {
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

    /**
     * O próximo lembrete vai sair sem som, sem vibração e sem aparecer sobre a tela?
     * Acontece quando o app está sem permissão de notificação, quando o canal foi desligado
     * ou quando foi rebaixado nas configurações do aparelho. A resposta vem do canal gravado,
     * não da constante: o sistema ignora uma criação que tente subir a importância de volta.
     *
     * O aplicativo NÃO toca som próprio quando esta resposta é `true`, e é de propósito. Uma
     * notificação bloqueada não sai de jeito nenhum, e um `MediaPlayer` não conserta isso —
     * ele só passaria por cima do Modo Silencioso e do Não Perturbe, que é justamente o que
     * uma pessoa idosa liga de propósito (à noite, no médico, na igreja). Trocar o silêncio
     * dela por um alarme nosso seria desfazer uma escolha que ela fez. O que o aplicativo
     * deve a ela é saber que está mudo e dizer, com um toque que resolva — é o cartão de
     * avisos da home.
     */
    fun remindersWillBeSilent(context: Context): Boolean =
        reminderAlerts(context) != ReminderAlerts.OK

    /**
     * O estado dos avisos: a mesma pergunta que a home faz para decidir se mostra o cartão e
     * qual ação ele oferece. Vive aqui, e não na tela, porque é a mesma sondagem que o
     * lembrete faz antes de sair — duas respostas diferentes para "os avisos estão valendo?"
     * seriam a home dizendo que está tudo bem enquanto o lembrete não sai.
     *
     * A permissão vem do [NotificationManagerCompat], que é quem enxerga o "desligado nas
     * configurações" além da permissão negada; a importância vem do canal gravado. `minSdk`
     * é 26, então canal sempre existe.
     */
    fun reminderAlerts(context: Context): ReminderAlerts {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return ReminderAlerts.OFF
        if (channelDisabled(context)) return ReminderAlerts.OFF
        val importance = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)?.importance ?: return ReminderAlerts.OK
        return if (importance < NotificationManager.IMPORTANCE_DEFAULT) ReminderAlerts.QUIET else ReminderAlerts.OK
    }

    /**
     * O canal foi DESLIGADO nas configurações — [NotificationManager.IMPORTANCE_NONE]. Não é o
     * mesmo que rebaixado: um canal desligado não exibe notificação nenhuma, então quem o conta
     * como "sem som" registra como entregue um aviso que ela nunca viu, gasta o degrau da escada
     * e para de insistir.
     */
    private fun channelDisabled(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE

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
        // Canal desligado também não emite nada. Sem esta saída o `notify` daqui de baixo não
        // lançaria nada e a resposta seria POSTED: o degrau era gasto e a repetição seguinte
        // armada — para o sistema descartar as duas, calado.
        if (channelDisabled(context)) {
            Log.w(TAG, "Lembrete $occurrenceId não emitido: canal $CHANNEL_ID desligado")
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

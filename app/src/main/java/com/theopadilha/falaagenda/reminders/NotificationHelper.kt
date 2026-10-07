package com.theopadilha.falaagenda.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.theopadilha.falaagenda.R

object NotificationHelper {
    /**
     * O canal dos lembretes. O id mudou junto com o som, e isso é a migração, não uma renomeação:
     * um canal já criado **não muda de som** — o sistema só aceita nome e descrição de uma criação
     * repetida — e apagar para recriar com o mesmo id também não resolve, porque o sistema
     * **restaura as configurações anteriores do canal apagado** quando um canal com o mesmo id
     * volta a nascer. O aparelho dela tem o canal antigo ([LEGACY_CHANNEL_ID]) gravado, com o som
     * padrão de notificação e possivelmente com o som que ela pôs em "Nenhum"; um id novo é o único
     * jeito de ele passar a tocar o som de alarme. Sem isso o fix valeria só em instalação limpa.
     */
    const val CHANNEL_ID = "fala_agenda_alarmes"

    /**
     * O canal anterior. Fica aqui como fato histórico do aparelho, e não como constante de uso: o
     * [ensureChannel] apaga este id porque ele é o canal mudo que a versão antiga criou, e deixá-lo
     * nas configurações dela só ofereceria um "Lembretes" silencioso para escolher por engano.
     */
    private const val LEGACY_CHANNEL_ID = "fala_agenda_reminders"

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
     * [QUIET] é o canal mudo: a notificação sai, mas não faz barulho, e para quem depende dela ser
     * lembrada isso é quase o mesmo que não sair. São dois caminhos até aqui — o canal rebaixado
     * nas configurações e o canal que continua alto com o som posto em "Nenhum" —, e o segundo é
     * o que passava como saudável.
     */
    enum class ReminderAlerts { OK, OFF, QUIET }

    /**
     * O canal do lembrete, que é um alarme — e não um aviso de mensagem.
     *
     * Antes ele subia sem som próprio, e o único áudio era o plim curto do som padrão de
     * notificação, que toca uma vez e para: um lembrete das 08:00, na cozinha, com o celular na
     * sala, não é um alarme. Aqui o canal toca o som de alarme do próprio aparelho
     * ([RingtoneManager.TYPE_ALARM]) e o `AudioAttributes` sobe em [AudioAttributes.USAGE_ALARM].
     *
     * A escolha do som do aparelho, e não de um arquivo empacotado, é de propósito: é o som que ela
     * já reconhece como despertador, não engorda o APK e continua sendo o que ela escolheria nos
     * Ajustes. O `USAGE_ALARM` também é o que põe o toque no volume de alarme — e é por isso que o
     * lembrete **não** é silenciado junto com as notificações comuns, que costumam estar mudas ou
     * baixas. Pelo mesmo motivo o som vai no canal, e não na notificação: a partir do Android 8 o
     * som de uma notificação é o do canal, e `setSound` na notificação é ignorado.
     *
     * O canal antigo é apagado na passagem (ver [CHANNEL_ID]). Como um canal apagado com o mesmo id
     * volta com as configurações antigas, a única saída é o id novo — o canal da versão anterior
     * fica mudo para sempre no aparelho dela.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val somDeAlarme = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioDeAlarme = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Avisos de tarefas no horário combinado"
            setSound(somDeAlarme, audioDeAlarme)
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
     * configurações" além da permissão negada; a importância e o som vêm do canal gravado. `minSdk`
     * é 26, então canal existe — exceto antes do primeiro agendamento, e é por isso que canal
     * ausente responde [ReminderAlerts.OK] e não [ReminderAlerts.QUIET]: "ainda não criado" não é
     * "mudo", e inventar um aviso falso para ela seria o mesmo defeito na direção oposta.
     *
     * A ordem importa: o canal DESLIGADO sai primeiro porque ele não é "sem som" — com
     * [NotificationManager.IMPORTANCE_NONE] a notificação não aparece de forma nenhuma, e contá-lo
     * como mudo faria o app dizer "sem som" enquanto o remédio das 08:00 nunca mais saía.
     */
    fun reminderAlerts(context: Context): ReminderAlerts {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return ReminderAlerts.OFF
        val canal = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID) ?: return ReminderAlerts.OK
        if (canal.importance == NotificationManager.IMPORTANCE_NONE) return ReminderAlerts.OFF
        if (canal.importance < NotificationManager.IMPORTANCE_DEFAULT) return ReminderAlerts.QUIET
        // Canal alto mas mudo é o mesmo desfecho que canal rebaixado: o lembrete sai, ninguém ouve.
        return if (channelSilenced(canal)) ReminderAlerts.QUIET else ReminderAlerts.OK
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

    /**
     * O canal está alto mas mudo: o som foi posto em "Nenhum" nas configurações do aparelho (ou o
     * padrão do sistema está em "Nenhum", e o canal herda isso na criação). Um canal
     * `IMPORTANCE_HIGH` nesse estado passava como saudável, o cartão "sem som" da home não
     * aparecia, e o lembrete saía mudo com o aplicativo achando que tinha avisado.
     *
     * As duas leituras entram porque uma só não cobre tudo: `sound` é nulo quando o som foi
     * desligado, e `shouldVibrate` cobre o canal que perdeu o som e a vibração de uma vez — que é
     * o "Nenhum" de verdade. Um canal que ainda vibra não está mudo, mesmo sem som.
     */
    private fun channelSilenced(canal: NotificationChannel): Boolean =
        canal.sound == null && !canal.shouldVibrate()

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
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Não sai no swipe acidental: um aviso de remédio que ela apaga sem ler, sem querer,
            // morre calado. O caminho de dispensar continua sendo os botões daqui de baixo —
            // "Concluir" e "Adiar 30 min" —, que é o que `setOngoing` não bloqueia.
            .setOngoing(true)
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
     * Ela tocou num botão da notificação e a ação não valeu: a ocorrência saiu da agenda entre o
     * aviso e o toque, ou o trabalho não terminou a tempo de responder. Sem isto o lembrete só
     * desaparecia — e, no "adiar", ela ficava esperando um aviso que ninguém agendou. No remédio,
     * o remédio que não toca.
     *
     * Sai no mesmo canal do lembrete, que é onde ela já sabe procurar; um canal novo não teria
     * som nem permissão garantidos. Não leva botões: a ação que ela tocou é justamente a que
     * não pegou, e oferecê-la de novo só repetiria a falha.
     *
     * Vale o mesmo guard do [showReminder], e aqui ele pesa mais: este aviso existe só para
     * dizer que a ação dela não pegou. Com o canal desligado o sistema o descartaria calado —
     * ela tocaria em "Adiar", nada seria agendado, e ela ficaria esperando um aviso que não
     * vem sem nunca saber por quê. O desfecho [ReminderDelivery.BLOCKED] diz isso a quem
     * chamou.
     *
     * O que [resposta] separa é o texto, e não se publica ou não: [ActionResponse.GONE] é o fato
     * de a ocorrência ter saído da agenda; [ActionResponse.UNFINISHED] é o trabalho que estourou o
     * tempo, e ali afirmar o que foi gravado seria chute.
     */
    fun showActionNotApplied(
        context: Context,
        occurrenceId: String,
        action: String,
        resposta: ActionResponse,
    ): ReminderDelivery {
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "Aviso de ação não aplicada $occurrenceId não emitido: notificações bloqueadas")
            return ReminderDelivery.BLOCKED
        }
        if (channelDisabled(context)) {
            Log.w(TAG, "Aviso de ação não aplicada $occurrenceId não emitido: canal $CHANNEL_ID desligado")
            return ReminderDelivery.BLOCKED
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(actionNotAppliedTitle(action)))
            .setContentText(context.getString(actionNotAppliedText(action, resposta)))
            .setContentIntent(openPending(context, occurrenceId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(
                AlarmIds.requestCode(occurrenceId, AlarmIds.NOTIF_NOT_APPLIED),
                notification,
            )
            // Aqui ela precisa notar: um aviso mudo é quase tão ruim quanto nenhum.
            if (remindersWillBeSilent(context)) {
                Log.w(TAG, "Aviso de ação não aplicada $occurrenceId apareceu sem som: canal rebaixado")
            }
            ReminderDelivery.POSTED
        } catch (e: SecurityException) {
            Log.w(TAG, "Aviso de ação não aplicada $occurrenceId recusado pelo sistema", e)
            ReminderDelivery.FAILED
        }
    }

    /**
     * O aviso muda com o que ela tentou fazer: "concluir" e "adiar" terminam em histórias
     * diferentes na cabeça dela — no "adiar" o que não pode ficar por dizer é que outro aviso não
     * vai tocar. Ação sem texto próprio cai no genérico: vago é melhor do que mudo.
     *
     * O título não muda com [ActionResponse], e de propósito: "Não deu para concluir" já diz o
     * essencial nos dois casos, e é o texto que precisa separar o fato do chute.
     */
    fun actionNotAppliedTitle(action: String): Int = when (action) {
        AlarmIds.ACTION_COMPLETE -> R.string.action_not_applied_title_complete
        AlarmIds.ACTION_SNOOZE -> R.string.action_not_applied_title_snooze
        else -> R.string.action_not_applied_title
    }

    /**
     * O texto muda também com o que se sabe do resultado. No [ActionResponse.GONE] a ocorrência
     * saiu da agenda, e dizer isso é dizer o que aconteceu; no [ActionResponse.UNFINISHED] o
     * trabalho não terminou e ninguém sabe o que foi gravado — repetir ali "esta tarefa não está
     * mais na agenda" seria anunciar uma causa que o aplicativo não verificou. O que se pode dizer
     * é o que se sabe: não terminou, e ela precisa conferir.
     */
    fun actionNotAppliedText(action: String, resposta: ActionResponse): Int = when (resposta) {
        ActionResponse.GONE -> when (action) {
            AlarmIds.ACTION_COMPLETE -> R.string.action_not_applied_text_complete
            AlarmIds.ACTION_SNOOZE -> R.string.action_not_applied_text_snooze
            else -> R.string.action_not_applied_text
        }
        ActionResponse.UNFINISHED -> when (action) {
            AlarmIds.ACTION_COMPLETE -> R.string.action_unfinished_text_complete
            AlarmIds.ACTION_SNOOZE -> R.string.action_unfinished_text_snooze
            else -> R.string.action_unfinished_text
        }
        // [ActionResponse.RESOLVIDA] é "não há o que dizer", e por isso não chega aqui: o aviso só
        // é publicado quando `precisaAvisarDeAcaoNaoAplicada` responde que há. Cai no genérico,
        // como a ação sem texto próprio — vago é melhor do que mudo.
        ActionResponse.RESOLVIDA -> R.string.action_not_applied_text
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

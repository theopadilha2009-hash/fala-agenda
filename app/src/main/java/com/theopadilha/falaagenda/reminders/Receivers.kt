package com.theopadilha.falaagenda.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.theopadilha.falaagenda.FalaAgendaApplication
import com.theopadilha.falaagenda.data.repo.ActionOutcome
import com.theopadilha.falaagenda.data.repo.Delivery
import com.theopadilha.falaagenda.widget.AgendaWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "FalaAgendaReceiver"

// Acima disso o sistema considera o receiver travado e mata o processo com o trabalho pendente.
private const val WORK_TIMEOUT_MS = 8_000L

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID) ?: return
        // O contexto e o escopo são resolvidos antes do `goAsync`, como no [ReminderActionReceiver]:
        // um `pending` já pedido depende do `finally` para ser encerrado, e nem um contexto que não
        // é o do aplicativo (`as?` devolve `null`) nem um escopo já cancelado chegam a rodá-lo. Com
        // o cast cru, um processo que subisse com outro `Application` derrubava o receiver do alarme
        // em vez de falhar calado — o lembrete não tocava e ninguém dizia por quê.
        val app = context.applicationContext as? FalaAgendaApplication
        if (app == null || !app.appScope.isActive) {
            Log.w(TAG, "Sem escopo para tratar o lembrete $occurrenceId")
            return
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeout(WORK_TIMEOUT_MS) {
                    app.container.tasks.onAlarmFired(occurrenceId) { title, seriesId ->
                        // O motivo do aviso não sair (permissão negada, canal desligado, sistema
                        // recusando) mora no NotificationHelper, e é lá que ele entra no log —
                        // junto do occurrenceId. Logar de novo aqui só repetiria a linha.
                        when (NotificationHelper.showReminder(context, occurrenceId, seriesId, title)) {
                            NotificationHelper.ReminderDelivery.POSTED -> Delivery.ARRIVED
                            NotificationHelper.ReminderDelivery.BLOCKED -> Delivery.BLOCKED
                            NotificationHelper.ReminderDelivery.FAILED -> Delivery.FAILED
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Tempo esgotado ao tratar o lembrete $occurrenceId")
                agendarRecuperacao(app, occurrenceId)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao tratar o lembrete $occurrenceId", e)
                agendarRecuperacao(app, occurrenceId)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * O trabalho não terminou: sem isto a escada de repetições morria em silêncio até o
     * app ser aberto de novo. Não suspende de propósito — precisa caber antes do
     * `finish()` do goAsync.
     */
    private fun agendarRecuperacao(app: FalaAgendaApplication, occurrenceId: String) {
        try {
            app.container.tasks.scheduleRecovery(occurrenceId)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível agendar a recuperação do lembrete $occurrenceId", e)
        }
    }
}

/**
 * As ações que o botão da notificação sabe pedir. São um tipo, e não as strings soltas de
 * [AlarmIds], porque é o compilador que precisa cobrar um desfecho para cada uma: uma ação nova
 * não pode cair, sem ninguém notar, num caminho que responde outra coisa.
 */
internal enum class AcaoDaNotificacao(val id: String) {
    CONCLUIR(AlarmIds.ACTION_COMPLETE),
    ADIAR(AlarmIds.ACTION_SNOOZE);

    companion object {
        /** A ação pedida, ou `null` quando não é nenhuma destas. */
        fun de(action: String?): AcaoDaNotificacao? = entries.firstOrNull { it.id == action }
    }
}

/**
 * Como terminou o trabalho do [ReminderActionReceiver] sobre o toque dela.
 *
 * [ActionOutcome] não serve sozinho: ele só fala do que o repositório respondeu, e o desfecho que
 * mais importa aqui não vem de lá — o trabalho que estourou o tempo ou caiu antes de responder, e
 * que deixa o resultado desconhecido. Ele precisa de nome próprio porque é dele que sai o texto do
 * aviso: no [GONE] a ocorrência saiu da agenda, e isso é fato; no [UNFINISHED] afirmar o que foi
 * gravado seria chute.
 *
 * [RESOLVIDA] junta os dois desfechos do repositório em que não há nada a dizer — foi gravado, ou
 * já estava no estado pedido — porque, para quem decide falar ou calar, os dois são a mesma coisa:
 * silêncio.
 */
enum class ActionResponse {
    /** A ação foi gravada, ou já estava no estado pedido: não há o que dizer nem o que corrigir. */
    RESOLVIDA,

    /** A ocorrência saiu da agenda: nada foi gravado, e é isso que ela precisa saber. */
    GONE,

    /** O trabalho não terminou (tempo esgotado, falha): o que foi gravado é desconhecido. */
    UNFINISHED,
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val acao = AcaoDaNotificacao.de(intent.action)
        if (acao == null) {
            // Nada disto acontece hoje: o `PendingIntent` do lembrete só nasce com "concluir" e
            // "adiar". Antes, uma ação que não fosse nenhuma das duas atravessava o `when` sem
            // casar com nada e caía no `cancel` — a notificação do lembrete sumia da barra sem
            // nada ter sido aplicado nem dito, e a tarefa continuava na agenda. Com um pedido que
            // não se entendeu, o certo é não responder: não aplica, não cancela e não diz nada.
            Log.w(TAG, "Ação desconhecida na notificação do lembrete: ${intent.action}")
            return
        }
        val occurrenceId = intent.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID) ?: return
        // O contexto e o escopo são resolvidos antes do `goAsync` de propósito: um `pending` já
        // pedido depende do `finally` lá de baixo para ser encerrado, e nem um contexto que não é
        // o do aplicativo (`as?` devolve `null`) nem um escopo já cancelado — em que `launch`
        // devolve um job morto e o corpo nunca começa — chegam a rodá-lo. Com o `pending` pedido
        // antes, o receiver ficaria vivo até o sistema matar o processo.
        val app = context.applicationContext as? FalaAgendaApplication
        if (app == null || !app.appScope.isActive) {
            Log.w(TAG, "Sem escopo para responder o lembrete $occurrenceId")
            return
        }
        val pending = goAsync()
        app.appScope.launch {
            try {
                val resposta = try {
                    withTimeout(WORK_TIMEOUT_MS) { aplicar(app, acao, occurrenceId) }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Tempo esgotado ao responder o lembrete $occurrenceId")
                    // O trabalho foi interrompido no meio: nada garante que a ação gravou. Sem
                    // este desfecho o caminho terminava em silêncio, com a notificação do
                    // lembrete presa na barra como se ela não tivesse tocado em nada.
                    ActionResponse.UNFINISHED
                } catch (cancellation: CancellationException) {
                    // Cancelar o escopo é controle de fluxo, não falha do trabalho. Sem esta
                    // reexposição o `catch` de baixo (que é `Exception`, e cancelamento é uma)
                    // transformaria um encerramento deliberado do aplicativo em desfecho: diria a
                    // ela que a ação não pegou e ainda cancelaria o lembrete, por causa de algo que
                    // ela não fez. Vem depois do `TimeoutCancellationException`, que é filho dele e
                    // tem desfecho próprio.
                    throw cancellation
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao responder o lembrete $occurrenceId", e)
                    ActionResponse.UNFINISHED
                }
                encerrar(context, acao, occurrenceId, resposta)
            } finally {
                pending.finish()
            }
        }
    }

    /** Aplica o que ela pediu e traduz o que o repositório respondeu. */
    private suspend fun aplicar(
        app: FalaAgendaApplication,
        acao: AcaoDaNotificacao,
        occurrenceId: String,
    ): ActionResponse = when (acao) {
        AcaoDaNotificacao.CONCLUIR -> app.container.tasks.complete(occurrenceId).paraResposta()
        AcaoDaNotificacao.ADIAR -> app.container.tasks.snooze(occurrenceId, 30).paraResposta()
    }

    /**
     * Fecha o toque dela: diz o que houver de ser dito e decide o destino do lembrete na barra.
     *
     * A ordem é a regra. O aviso sai primeiro, e é o desfecho dele que decide se o lembrete pode
     * sair junto: é isso que garante que nunca se apague a última coisa na tela que conta a ela
     * que o toque não valeu.
     */
    private fun encerrar(
        context: Context,
        acao: AcaoDaNotificacao,
        occurrenceId: String,
        resposta: ActionResponse,
    ) {
        // Sem fala pendente, "o aviso saiu" é verdadeiro por definição: não há o que publicar.
        val avisoSaiu = !precisaAvisarDeAcaoNaoAplicada(resposta) ||
            publicarOaviso(context, acao, occurrenceId, resposta)
        if (deveCancelarOLembrete(resposta, avisoSaiu)) {
            NotificationHelper.cancel(context, occurrenceId)
        } else {
            // Fica na barra de propósito, e é a única coisa que sobra na tela contando a ela que
            // o toque não valeu — cancelar aqui devolveria o silêncio que este caminho conserta.
            Log.w(TAG, "A ação ${acao.id} não pegou e o aviso não saiu: o lembrete fica na barra")
        }
    }

    /**
     * A ocorrência saiu da agenda — ou o trabalho não terminou — e o toque no botão da notificação
     * não pegou. Um receiver não tem tela: quem fala com ela é a notificação, e a do lembrete já
     * vai embora daqui. Por isso o desfecho sai numa notificação própria — sem ela, no "adiar",
     * nada foi agendado e o aviso que ela esperava não vem: no remédio, o remédio que não toca.
     *
     * Devolve se a fala saiu de fato. O aviso tem id próprio, e não é ele que
     * [NotificationHelper.cancel] apaga.
     */
    private fun publicarOaviso(
        context: Context,
        acao: AcaoDaNotificacao,
        occurrenceId: String,
        resposta: ActionResponse,
    ): Boolean {
        Log.w(TAG, "A ação ${acao.id} da notificação não pegou ($resposta): $occurrenceId")
        return NotificationHelper.showActionNotApplied(context, occurrenceId, acao.id, resposta) ==
            NotificationHelper.ReminderDelivery.POSTED
    }
}

/** A tradução do que o repositório respondeu para o que o receiver tem a dizer. */
internal fun ActionOutcome.paraResposta(): ActionResponse = when (this) {
    ActionOutcome.APPLIED, ActionOutcome.UNCHANGED -> ActionResponse.RESOLVIDA
    ActionOutcome.GONE -> ActionResponse.GONE
}

/**
 * Ela precisa saber que a ação da notificação não pegou? Só quando nada foi gravado — no
 * [ActionResponse.GONE], em que a ocorrência saiu da agenda — ou quando não se sabe o que foi
 * gravado, no [ActionResponse.UNFINISHED].
 *
 * [ActionResponse.RESOLVIDA] cala de propósito: concluir o que já estava concluído é no-op
 * legítimo, não falha — anunciá-lo seria trocar a mentira pelo alarme falso.
 */
internal fun precisaAvisarDeAcaoNaoAplicada(resposta: ActionResponse): Boolean = when (resposta) {
    ActionResponse.GONE, ActionResponse.UNFINISHED -> true
    ActionResponse.RESOLVIDA -> false
}

/**
 * Tira o lembrete da barra depois que o toque dela foi respondido?
 *
 * Sim quando não havia o que dizer — a ação foi gravada — e sim quando o que havia de ser dito
 * saiu de fato ([avisoSaiu]). Não quando havia o que dizer e o aviso não pôde ser publicado: aí o
 * lembrete na barra é a única evidência que resta de que o toque dela não valeu, e apagá-lo
 * devolveria o silêncio que este caminho existe para consertar.
 *
 * É esta a preocupação que sempre esteve por trás de não cancelar — "cancelar apagaria a
 * evidência" —, e ela só se sustenta enquanto não se diz nada. Dito o que havia de ser dito, o
 * lembrete na barra vira só uma oferta a mais de uma ação que acabou de falhar, e sai.
 */
internal fun deveCancelarOLembrete(resposta: ActionResponse, avisoSaiu: Boolean): Boolean =
    !precisaAvisarDeAcaoNaoAplicada(resposta) || avisoSaiu

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
            // `ACTION_DATE_CHANGED` já esteve aqui e no manifesto, e nunca chegou: ele não
            // está na lista de exceções do broadcast implícito do Android 8 (o app tem
            // targetSdk 36), e receiver de manifesto não recebe broadcast implícito fora
            // dela. No grupo do relógio só `TIME_SET`, `TIMEZONE_CHANGED` e
            // `NEXT_ALARM_CLOCK_CHANGED` são exceção — é por isso que a troca de hora
            // funcionava e a virada do dia não. Quem virou o dia é o `DailySweepReceiver`.
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> rescheduleAsync(context)
        }
    }
}

/**
 * A virada do dia. Nenhum broadcast do sistema avisa que a data mudou (ver
 * [TimeChangeReceiver]), então quem avisa é um alarme exato nosso — armado por todo
 * `rescheduleAll`: start do processo, boot, troca de hora e a própria virada.
 *
 * É ele que faz a ocorrência de ontem virar "não realizada" e o widget parar de anunciar o
 * dia velho — e é por isso que ele repinta o widget junto: a virada do dia não muda o banco,
 * e é o banco que repinta o widget (ver `collectWidgetUpdates`).
 */
class DailySweepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = rescheduleAsync(context)
}

private fun BroadcastReceiver.rescheduleAsync(context: Context) {
    // Mesma guarda do receiver do alarme: sem o `as?`, um processo que subisse com outro
    // `Application` derrubava boot, troca de hora e virada do dia no ato do cast — em vez de
    // deixar a varredura de fora e registrar por quê. O escopo morto entra na mesma checagem:
    // com ele cancelado o `launch` devolve um job que nunca começa, e o `pending` pedido
    // abaixo ficaria vivo até o sistema matar o processo.
    val app = context.applicationContext as? FalaAgendaApplication
    if (app == null || !app.appScope.isActive) {
        Log.w(TAG, "Sem escopo para regravar os alarmes")
        return
    }
    val pending = goAsync()
    app.appScope.launch {
        try {
            app.container.tasks.rescheduleAll()
            // O relógio mudou e o banco não: sem este empurrão o widget seguia anunciando
            // "Hoje · 08:00" com a data de ontem e "Próxima" para o que já passou.
            AgendaWidgetProvider.refreshNow(context)
        } catch (e: Exception) {
            // Sem o catch a exceção subia pelo appScope e derrubava o processo no boot
            // e na troca de hora.
            Log.w(TAG, "Falha ao regravar os alarmes", e)
        } finally {
            pending.finish()
        }
    }
}

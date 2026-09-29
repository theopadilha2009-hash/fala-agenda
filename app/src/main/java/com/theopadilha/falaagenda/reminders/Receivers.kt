package com.theopadilha.falaagenda.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.theopadilha.falaagenda.FalaAgendaApplication
import com.theopadilha.falaagenda.data.repo.ActionOutcome
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

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val occurrenceId = intent.getStringExtra(AlarmIds.EXTRA_OCCURRENCE_ID) ?: return
        val pending = goAsync()
        val app = context.applicationContext as FalaAgendaApplication
        app.appScope.launch {
            try {
                withTimeout(WORK_TIMEOUT_MS) {
                    when (intent.action) {
                        AlarmIds.ACTION_COMPLETE -> registrarSeNaoPegou(
                            context,
                            AlarmIds.ACTION_COMPLETE,
                            occurrenceId,
                            app.container.tasks.complete(occurrenceId),
                        )
                        AlarmIds.ACTION_SNOOZE -> registrarSeNaoPegou(
                            context,
                            AlarmIds.ACTION_SNOOZE,
                            occurrenceId,
                            app.container.tasks.snooze(occurrenceId, 30),
                        )
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

    /**
     * A ocorrência saiu da agenda (ou já não aceita mais aquela ação) e o toque no botão da
     * notificação não pegou. Um receiver não tem tela: quem fala com ela é a notificação, e a do
     * lembrete já vai embora daqui. Por isso o desfecho sai numa notificação própria — sem ela,
     * no "adiar", nada foi agendado e o aviso que ela esperava não vem: no remédio, o remédio
     * que não toca.
     */
    private fun registrarSeNaoPegou(
        context: Context,
        acao: String,
        occurrenceId: String,
        desfecho: ActionOutcome,
    ) {
        if (!precisaAvisarDeAcaoNaoAplicada(desfecho)) return
        Log.w(TAG, "A ação $acao da notificação não pegou: $occurrenceId não está mais na agenda")
        NotificationHelper.showActionNotApplied(context, occurrenceId, acao)
    }
}

/**
 * Ela precisa saber que a ação da notificação não pegou? Só no [ActionOutcome.GONE]: ali nada foi
 * gravado — e, no "adiar", nada foi agendado, então o aviso que ela espera não vem.
 *
 * [ActionOutcome.UNCHANGED] cala de propósito: concluir o que já estava concluído é no-op
 * legítimo, não falha — anunciá-lo seria trocar a mentira pelo alarme falso.
 */
internal fun precisaAvisarDeAcaoNaoAplicada(desfecho: ActionOutcome): Boolean =
    desfecho == ActionOutcome.GONE

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
        } catch (e: Exception) {
            // Sem o catch a exceção subia pelo appScope e derrubava o processo no boot
            // e na troca de hora.
            Log.w(TAG, "Falha ao regravar os alarmes", e)
        } finally {
            pending.finish()
        }
    }
}

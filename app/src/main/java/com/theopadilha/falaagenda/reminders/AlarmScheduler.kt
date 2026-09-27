package com.theopadilha.falaagenda.reminders

import com.theopadilha.falaagenda.data.repo.SchedulerOutcome
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import java.time.Instant

interface AlarmScheduler {
    suspend fun quietHours(): QuietHours
    fun canScheduleExact(): Boolean
    fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean): SchedulerOutcome

    /**
     * Cancela os alarmes e a notificação publicada: a ocorrência deixou de existir
     * (concluída, excluída, série encerrada). Reagendar é outra coisa — ver [schedule].
     */
    fun cancel(occurrenceId: String)

    /**
     * Rede de segurança: marcado para [at], reprocessa o disparo desta ocorrência. Não é
     * ocorrência nova nem toca na notificação — é o mesmo alarme tocando de novo.
     */
    fun scheduleRecovery(occurrenceId: String, at: Instant)
}

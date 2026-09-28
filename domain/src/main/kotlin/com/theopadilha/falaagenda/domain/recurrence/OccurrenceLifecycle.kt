package com.theopadilha.falaagenda.domain.recurrence

import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.reminder.ReminderPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class LifecycleChange(
    val upserts: List<TaskOccurrence> = emptyList(),
    val markMissed: List<TaskOccurrence> = emptyList(),
    val cancelAlarmsOf: List<String> = emptyList(),
)

object OccurrenceLifecycle {
    // atalho: tombstone guarda só os últimos 90 dias e no máximo 120 datas, sempre as mais
    // próximas de hoje; revisitar se editar/concluir ocorrência muito antiga voltar a
    // rematerializar uma data excluída
    const val SKIPPED_RETENTION_DAYS = 90L
    const val MAX_SKIPPED_DATES = 120

    fun scheduledInstant(series: TaskSeries, localDate: LocalDate): Instant =
        ZonedDateTime.of(localDate, series.localTime, series.zoneId).toInstant()

    /** Marca a data como excluída, descartando tombstones velhos demais para voltar a valer. */
    fun skipDate(
        skipped: Set<LocalDate>,
        localDate: LocalDate,
        todayInSeriesZone: LocalDate,
    ): Set<LocalDate> {
        val limite = todayInSeriesZone.minusDays(SKIPPED_RETENTION_DAYS)
        // O teto corta as datas mais DISTANTES de hoje: são as próximas que o preview/advance
        // vão materializar, e uma tombstone descartada aí faz a data excluída renascer.
        return (skipped + localDate)
            .filter { !it.isBefore(limite) }
            .sortedWith(
                compareBy<LocalDate> { kotlin.math.abs(ChronoUnit.DAYS.between(todayInSeriesZone, it)) }
                    .thenByDescending { it },
            )
            .take(MAX_SKIPPED_DATES)
            .toSet()
    }

    /** Desfaz a exclusão da data (usado quando o usuário desfaz ou remarca para o mesmo dia). */
    fun unskipDate(skipped: Set<LocalDate>, localDate: LocalDate): Set<LocalDate> =
        skipped - localDate

    fun materialize(
        series: TaskSeries,
        localDate: LocalDate,
        now: Instant,
        existing: TaskOccurrence? = null,
    ): TaskOccurrence {
        val scheduledAt = scheduledInstant(series, localDate)
        val first = ReminderPolicy.firstReminder(scheduledAt)
        if (existing != null) {
            val keepProgress = existing.status == OccurrenceStatus.PENDING &&
                (existing.lastReminderAt != null || existing.snoozedUntil != null || existing.reminderStep > 0)
            return existing.copy(
                scheduledAt = scheduledAt,
                nextReminderAt = when {
                    existing.status != OccurrenceStatus.PENDING -> existing.nextReminderAt
                    keepProgress -> existing.nextReminderAt
                    else -> first.fireAt
                },
            )
        }
        return TaskOccurrence(
            id = OccurrenceIds.of(series.id, localDate),
            seriesId = series.id,
            localDate = localDate,
            scheduledAt = scheduledAt,
            status = OccurrenceStatus.PENDING,
            reminderStep = ReminderPolicy.STEP_FIRST,
            nextReminderAt = first.fireAt,
        )
    }

    /**
     * Se a ocorrência corrente ainda está pendente quando a próxima nasce,
     * marca a anterior como não realizada, cancela cobrança e inicia a nova.
     */
    fun advance(
        series: TaskSeries,
        existing: List<TaskOccurrence>,
        now: Instant,
        todayInSeriesZone: LocalDate,
    ): LifecycleChange {
        if (series.isEnded) {
            val pending = existing.filter { it.status == OccurrenceStatus.PENDING }
            return LifecycleChange(
                markMissed = emptyList(),
                cancelAlarmsOf = pending.map { it.id },
                upserts = pending.map {
                    it.copy(
                        status = OccurrenceStatus.CANCELLED,
                        nextReminderAt = null,
                    )
                },
            )
        }

        val dueDate = RecurrenceEngine.firstOnOrAfter(
            series.recurrence,
            series.startLocalDate,
            todayInSeriesZone,
        )

        val byDate = existing.associateBy { it.localDate }
        val markMissed = mutableListOf<TaskOccurrence>()
        val upserts = mutableListOf<TaskOccurrence>()
        val cancel = mutableListOf<String>()

        // O lembrete adiado pelo horário de silêncio só toca às 08:00 do dia seguinte e o
        // adiamento do usuário pode cair depois da meia-noite: uma data vencida que ainda
        // tem aviso vivo é "vai tocar", não "não foi feita".
        existing.filter {
            it.status == OccurrenceStatus.PENDING && !temLembreteVivo(it, now) &&
                expirou(it, todayInSeriesZone, series.zoneId)
        }.forEach { stale ->
            val missed = stale.copy(
                status = OccurrenceStatus.MISSED,
                missedAt = now,
                nextReminderAt = null,
            )
            markMissed += missed
            upserts += missed
            cancel += stale.id
        }

        if (dueDate == null) {
            return LifecycleChange(
                upserts = upserts,
                markMissed = markMissed,
                cancelAlarmsOf = cancel,
            )
        }

        val currentExisting = byDate[dueDate]
        if (currentExisting == null) {
            // Data excluída pelo usuário não volta a nascer; a série segue no próximo dia.
            if (!series.isSkipped(dueDate)) upserts += materialize(series, dueDate, now)
        } else if (currentExisting.status == OccurrenceStatus.PENDING) {
            val refreshed = materialize(series, dueDate, now, currentExisting)
            if (refreshed != currentExisting) upserts += refreshed
        }

        return LifecycleChange(
            upserts = upserts,
            markMissed = markMissed,
            cancelAlarmsOf = cancel,
        )
    }

    /**
     * Ainda vai tocar: lembrete (ou adiamento) marcado para agora ou depois. O instante
     * exato conta como vivo — é justamente quando o alarme está sendo entregue.
     */
    private fun temLembreteVivo(occurrence: TaskOccurrence, now: Instant): Boolean =
        listOfNotNull(occurrence.nextReminderAt, occurrence.snoozedUntil).any { !it.isBefore(now) }

    /**
     * Sem lembrete vivo, a ocorrência vira não realizada quando o dia do último aviso
     * entregue também passou: `max(localDate, dia do último lembrete) < hoje`.
     *
     * O aviso adiado pelo silêncio toca às 08:00 do dia seguinte e é o último degrau da
     * escada. Olhar só a data da ocorrência arquivaria a de ontem como não realizada no
     * exato instante em que o aviso toca — e o "Adiar" da notificação, que só age em
     * ocorrência pendente, viraria no-op. Nunca tendo aviso entregue, vale a própria data
     * da ocorrência: ela expira na primeira varredura, como sempre.
     */
    private fun expirou(
        occurrence: TaskOccurrence,
        todayInSeriesZone: LocalDate,
        zoneId: ZoneId,
    ): Boolean {
        val diaDoUltimoAviso = occurrence.lastReminderAt?.atZone(zoneId)?.toLocalDate()
        val referencia = diaDoUltimoAviso
            ?.let { maxOf(occurrence.localDate, it) }
            ?: occurrence.localDate
        return referencia.isBefore(todayInSeriesZone)
    }

    fun todayIn(zoneId: ZoneId, now: Instant): LocalDate = now.atZone(zoneId).toLocalDate()
}

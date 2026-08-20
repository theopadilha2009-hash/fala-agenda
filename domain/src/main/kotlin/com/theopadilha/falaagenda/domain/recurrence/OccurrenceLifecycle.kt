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

data class LifecycleChange(
    val upserts: List<TaskOccurrence> = emptyList(),
    val markMissed: List<TaskOccurrence> = emptyList(),
    val cancelAlarmsOf: List<String> = emptyList(),
)

object OccurrenceLifecycle {
    fun scheduledInstant(series: TaskSeries, localDate: LocalDate): Instant =
        ZonedDateTime.of(localDate, series.localTime, series.zoneId).toInstant()

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
        ) ?: return LifecycleChange()

        val byDate = existing.associateBy { it.localDate }
        val markMissed = mutableListOf<TaskOccurrence>()
        val upserts = mutableListOf<TaskOccurrence>()
        val cancel = mutableListOf<String>()

        existing.filter {
            it.status == OccurrenceStatus.PENDING && it.localDate.isBefore(dueDate)
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

        val currentExisting = byDate[dueDate]
        if (currentExisting == null) {
            upserts += materialize(series, dueDate, now)
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

    fun todayIn(zoneId: ZoneId, now: Instant): LocalDate = now.atZone(zoneId).toLocalDate()
}

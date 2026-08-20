package com.theopadilha.falaagenda.data.repo

import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.toDomain
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.recurrence.OccurrenceLifecycle
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import com.theopadilha.falaagenda.domain.reminder.ReminderPolicy
import com.theopadilha.falaagenda.domain.time.AppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.util.UUID

data class AgendaItem(
    val occurrence: TaskOccurrence,
    val series: TaskSeries,
)

data class AgendaSections(
    val today: List<AgendaItem>,
    val upcoming: List<AgendaItem>,
    val completed: List<AgendaItem>,
    val missed: List<AgendaItem>,
) {
    fun find(occurrenceId: String): AgendaItem? =
        (today + upcoming + completed + missed).firstOrNull { it.occurrence.id == occurrenceId }
}

class TaskRepository(
    private val seriesDao: SeriesDao,
    private val occurrenceDao: OccurrenceDao,
    private val clock: AppClock,
    private val scheduler: AlarmScheduler,
) {
    fun observeAgenda(): Flow<AgendaSections> = combine(
        seriesDao.observeAll(),
        occurrenceDao.observeAll(),
    ) { seriesRows, occurrenceRows ->
        val series = seriesRows.associate { it.id to it.toDomain() }
        val items = occurrenceRows.mapNotNull { row ->
            val s = series[row.seriesId] ?: return@mapNotNull null
            AgendaItem(row.toDomain(), s)
        }
        val today = clock.today()
        val pending = items.filter { it.occurrence.status == OccurrenceStatus.PENDING }
        AgendaSections(
            today = pending.filter { it.occurrence.localDate == today }
                .sortedBy { it.occurrence.scheduledAt },
            upcoming = pending.filter { it.occurrence.localDate.isAfter(today) }
                .sortedBy { it.occurrence.scheduledAt },
            completed = items.filter { it.occurrence.status == OccurrenceStatus.COMPLETED }
                .sortedByDescending { it.occurrence.completedAt },
            missed = items.filter { it.occurrence.status == OccurrenceStatus.MISSED }
                .sortedByDescending { it.occurrence.missedAt },
        )
    }

    suspend fun saveDraft(draft: ParsedTaskDraft): SaveResult {
        require(draft.isComplete) { "Confirme título, data e horário antes de salvar." }
        val now = clock.instant()
        val zone = clock.zoneId()
        val series = TaskSeries(
            id = UUID.randomUUID().toString(),
            title = draft.title.trim(),
            zoneId = zone,
            localTime = draft.localTime!!,
            startLocalDate = draft.localDate!!,
            recurrence = draft.recurrence,
            createdAt = now,
            updatedAt = now,
        )
        val firstDate = RecurrenceEngine.firstOnOrAfter(
            series.recurrence,
            series.startLocalDate,
            series.startLocalDate,
        ) ?: series.startLocalDate
        var occurrence = OccurrenceLifecycle.materialize(series, firstDate, now)
        val scheduledAt = occurrence.scheduledAt
        if (scheduledAt.isBefore(now) && series.recurrence.kind == RecurrenceKind.NONE) {
            occurrence = occurrence.copy(
                status = OccurrenceStatus.MISSED,
                missedAt = now,
                nextReminderAt = null,
            )
        }
        seriesDao.upsert(series.toEntity())
        val scheduled = if (occurrence.status == OccurrenceStatus.PENDING) {
            scheduler.schedule(occurrence, series, first = true)
        } else {
            SchedulerOutcome(inexact = false, scheduled = false)
        }
        val stored = occurrence.copy(inexactAlarm = scheduled.inexact)
        occurrenceDao.upsert(stored.toEntity())
        return SaveResult(series = series, occurrence = stored, usedInexactAlarm = scheduled.inexact)
    }

    suspend fun complete(occurrenceId: String) {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return
        val occurrence = row.toDomain()
        scheduler.cancel(occurrence.id)
        val done = occurrence.copy(
            status = OccurrenceStatus.COMPLETED,
            completedAt = now,
            nextReminderAt = null,
        )
        occurrenceDao.upsert(done.toEntity())
        spawnNextIfNeeded(series, done.localDate, now)
    }

    suspend fun deleteOccurrence(occurrenceId: String) {
        val row = occurrenceDao.get(occurrenceId) ?: return
        scheduler.cancel(occurrenceId)
        occurrenceDao.delete(occurrenceId)
        val leftover = occurrenceDao.forSeries(row.seriesId)
        if (leftover.isEmpty()) {
            seriesDao.delete(row.seriesId)
        }
    }

    suspend fun restore(item: AgendaItem) {
        val now = clock.instant()
        val series = item.series.copy(endedAt = null, updatedAt = now)
        seriesDao.upsert(series.toEntity())
        val fresh = OccurrenceLifecycle.materialize(series, item.occurrence.localDate, now)
        if (fresh.scheduledAt.isBefore(now) && !series.recurrence.isRecurring) {
            occurrenceDao.upsert(
                fresh.copy(
                    status = OccurrenceStatus.MISSED,
                    missedAt = now,
                    nextReminderAt = null,
                ).toEntity(),
            )
            return
        }
        val scheduled = scheduler.schedule(fresh, series, first = true)
        occurrenceDao.upsert(fresh.copy(inexactAlarm = scheduled.inexact).toEntity())
    }

    suspend fun endSeries(seriesId: String) {
        val now = clock.instant()
        val series = seriesDao.get(seriesId)?.toDomain() ?: return
        val ended = series.copy(endedAt = now, updatedAt = now)
        seriesDao.upsert(ended.toEntity())
        occurrenceDao.forSeries(seriesId)
            .map { it.toDomain() }
            .filter { it.status == OccurrenceStatus.PENDING }
            .forEach {
                scheduler.cancel(it.id)
                occurrenceDao.upsert(
                    it.copy(status = OccurrenceStatus.CANCELLED, nextReminderAt = null).toEntity(),
                )
            }
    }

    suspend fun editOccurrence(
        occurrenceId: String,
        title: String,
        date: java.time.LocalDate,
        time: java.time.LocalTime,
        recurrence: com.theopadilha.falaagenda.domain.model.RecurrenceRule,
    ) {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return
        occurrenceDao.forSeries(series.id)
            .map { it.toDomain() }
            .filter { it.status == OccurrenceStatus.PENDING }
            .forEach {
                scheduler.cancel(it.id)
                occurrenceDao.delete(it.id)
            }
        val updatedSeries = series.copy(
            title = title.trim(),
            localTime = time,
            startLocalDate = date,
            recurrence = recurrence,
            updatedAt = now,
        )
        seriesDao.upsert(updatedSeries.toEntity())
        val refreshed = OccurrenceLifecycle.materialize(updatedSeries, date, now)
        if (refreshed.scheduledAt.isBefore(now) && !updatedSeries.recurrence.isRecurring) {
            occurrenceDao.upsert(
                refreshed.copy(
                    status = OccurrenceStatus.MISSED,
                    missedAt = now,
                    nextReminderAt = null,
                ).toEntity(),
            )
            return
        }
        val scheduled = scheduler.schedule(refreshed, updatedSeries, first = true)
        occurrenceDao.upsert(refreshed.copy(inexactAlarm = scheduled.inexact).toEntity())
        spawnUpcomingPreview(updatedSeries, date)
    }

    suspend fun snooze(occurrenceId: String, minutes: Long = 30) {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return
        val occurrence = row.toDomain()
        if (occurrence.status != OccurrenceStatus.PENDING) return
        val quiet = scheduler.quietHours()
        val plan = ReminderPolicy.snooze(now, minutes, series.zoneId, quiet, respectQuietHours = false)
        val updated = occurrence.copy(
            snoozedUntil = plan.fireAt,
            nextReminderAt = plan.fireAt,
            reminderStep = plan.step,
        )
        val scheduled = scheduler.schedule(updated, series, first = false)
        occurrenceDao.upsert(updated.copy(inexactAlarm = scheduled.inexact).toEntity())
    }

    suspend fun onAlarmFired(occurrenceId: String): AlarmFireResult {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return AlarmFireResult(false)
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return AlarmFireResult(false)
        applyLifecycle(series, now)
        val occurrence = occurrenceDao.get(occurrenceId)?.toDomain() ?: return AlarmFireResult(false)
        if (occurrence.status != OccurrenceStatus.PENDING) {
            scheduler.cancel(occurrenceId)
            return AlarmFireResult(false)
        }
        val quiet = scheduler.quietHours()
        if (occurrence.reminderStep > 0 && ReminderPolicy.isInQuietHours(now, series.zoneId, quiet)) {
            val resume = ReminderPolicy.shiftOutOfQuietHours(now, series.zoneId, quiet)
            val deferred = occurrence.copy(nextReminderAt = resume)
            val scheduled = scheduler.schedule(deferred, series, first = false)
            occurrenceDao.upsert(deferred.copy(inexactAlarm = scheduled.inexact).toEntity())
            return AlarmFireResult(false)
        }
        val nextStep = ReminderPolicy.nextStep(occurrence.reminderStep)
        val interval = ReminderPolicy.intervalAfterStep(occurrence.reminderStep)
        val plan = ReminderPolicy.nextRepetition(
            from = now,
            nextStep = nextStep,
            zoneId = series.zoneId,
            quietHours = quiet,
            interval = interval,
        )
        val updated = occurrence.copy(
            reminderStep = plan.step,
            lastReminderAt = now,
            nextReminderAt = plan.fireAt,
        )
        val scheduled = scheduler.schedule(updated, series, first = false)
        occurrenceDao.upsert(updated.copy(inexactAlarm = scheduled.inexact).toEntity())
        return AlarmFireResult(notify = true, title = series.title, seriesId = series.id)
    }

    suspend fun rescheduleAll() {
        val now = clock.instant()
        val seriesList = seriesDao.getAll().map { it.toDomain() }
        seriesList.forEach { series ->
            applyLifecycle(series, now)
        }
        occurrenceDao.getAll().map { it.toDomain() }
            .filter { it.status == OccurrenceStatus.PENDING }
            .forEach { occ ->
                val series = seriesDao.get(occ.seriesId)?.toDomain() ?: return@forEach
                val scheduled = scheduler.schedule(
                    occ,
                    series,
                    first = occ.reminderStep == 0 && occ.lastReminderAt == null,
                )
                occurrenceDao.upsert(occ.copy(inexactAlarm = scheduled.inexact).toEntity())
            }
    }

    private suspend fun applyLifecycle(series: TaskSeries, now: Instant) {
        val today = OccurrenceLifecycle.todayIn(series.zoneId, now)
        val existing = occurrenceDao.forSeries(series.id).map { it.toDomain() }
        val change = OccurrenceLifecycle.advance(series, existing, now, today)
        change.cancelAlarmsOf.forEach { scheduler.cancel(it) }
        change.upserts.forEach { occ ->
            occurrenceDao.upsert(occ.toEntity())
        }
        spawnUpcomingPreview(series, today)
    }

    private suspend fun spawnNextIfNeeded(series: TaskSeries, completedDate: java.time.LocalDate, now: Instant) {
        if (series.isEnded || !series.recurrence.isRecurring) return
        val nextDate = RecurrenceEngine.nextAfter(series.recurrence, series.startLocalDate, completedDate) ?: return
        val existing = occurrenceDao.get(com.theopadilha.falaagenda.domain.model.OccurrenceIds.of(series.id, nextDate))
        if (existing != null) return
        val next = OccurrenceLifecycle.materialize(series, nextDate, now)
        val scheduled = scheduler.schedule(next, series, first = true)
        occurrenceDao.upsert(next.copy(inexactAlarm = scheduled.inexact).toEntity())
    }

    private suspend fun spawnUpcomingPreview(series: TaskSeries, today: java.time.LocalDate) {
        if (series.isEnded || !series.recurrence.isRecurring) return
        RecurrenceEngine.upcoming(series.recurrence, series.startLocalDate, today, 3).forEach { date ->
            val id = com.theopadilha.falaagenda.domain.model.OccurrenceIds.of(series.id, date)
            if (occurrenceDao.get(id) == null) {
                val occ = OccurrenceLifecycle.materialize(series, date, clock.instant())
                val scheduled = scheduler.schedule(occ, series, first = true)
                occurrenceDao.upsert(occ.copy(inexactAlarm = scheduled.inexact).toEntity())
            }
        }
    }
}

data class SaveResult(
    val series: TaskSeries,
    val occurrence: TaskOccurrence,
    val usedInexactAlarm: Boolean,
)

data class SchedulerOutcome(
    val inexact: Boolean,
    val scheduled: Boolean,
)

data class AlarmFireResult(
    val notify: Boolean,
    val title: String = "",
    val seriesId: String = "",
)

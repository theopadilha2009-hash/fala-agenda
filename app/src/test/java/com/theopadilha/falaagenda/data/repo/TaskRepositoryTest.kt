package com.theopadilha.falaagenda.data.repo

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TaskRepositoryTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val seriesDao = FakeSeriesDao()
    private val occurrenceDao = FakeOccurrenceDao()
    private val scheduler = RecordingScheduler()
    private val repo = TaskRepository(seriesDao, occurrenceDao, clock, scheduler)

    @Test
    fun saveAndCompleteUniqueTask() = runBlocking {
        val draft = completeDraft("Tomar remédio", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0))
        val saved = repo.saveDraft(draft)
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
        assertThat(scheduler.scheduled).hasSize(1)
        repo.complete(saved.occurrence.id)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(row.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
        assertThat(scheduler.cancelled).contains(saved.occurrence.id)
    }

    @Test
    fun saveDraftGuardaValor() = runBlocking {
        val draft = completeDraft("Cabelo", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0))
            .copy(amountCents = 8000)
        val saved = repo.saveDraft(draft)
        assertThat(saved.series.amountCents).isEqualTo(8000)
        assertThat(seriesDao.get(saved.series.id)?.amountCents).isEqualTo(8000)
    }

    @Test
    fun pastUniqueBecomesMissed() = runBlocking {
        val draft = completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0))
        val saved = repo.saveDraft(draft)
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun editCancelsAndReschedules() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(14, 0)))
        repo.editOccurrence(
            saved.occurrence.id,
            "Consulta médica",
            LocalDate.of(2026, 8, 23),
            LocalTime.of(15, 0),
            RecurrenceRule(),
        )
        assertThat(scheduler.cancelled).contains(saved.occurrence.id)
        assertThat(scheduler.scheduled.size).isAtLeast(2)
    }

    @Test
    fun deniedExactAlarmStillSaves() = runBlocking {
        scheduler.exact = false
        val saved = repo.saveDraft(completeDraft("Farmácia", LocalDate.of(2026, 8, 21), LocalTime.of(11, 0)))
        assertThat(saved.usedInexactAlarm).isTrue()
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
    }

    @Test
    fun rebootReschedulesPending() = runBlocking {
        repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        scheduler.scheduled.clear()
        repo.rescheduleAll()
        assertThat(scheduler.scheduled).isNotEmpty()
    }

    @Test
    fun duasTarefasNoMesmoHorarioNaoColidem() = runBlocking<Unit> {
        val a = repo.saveDraft(completeDraft("A", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)))
        val b = repo.saveDraft(completeDraft("B", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)))
        assertThat(a.occurrence.id).isNotEqualTo(b.occurrence.id)
        assertThat(scheduler.scheduled).containsExactly(a.occurrence.id, b.occurrence.id)
    }

    @Test
    fun reschedulePreservaSnooze() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.snooze(saved.occurrence.id, 30)
        val afterSnooze = occurrenceDao.get(saved.occurrence.id)!!
        scheduler.scheduled.clear()
        repo.rescheduleAll()
        val again = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(again.nextReminderAtEpochMs).isEqualTo(afterSnooze.nextReminderAtEpochMs)
        assertThat(again.reminderStep).isEqualTo(afterSnooze.reminderStep)
        assertThat(scheduler.scheduled).contains(saved.occurrence.id)
    }

    @Test
    fun alarmeDeOcorrenciaConcluidaNaoNotifica() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.complete(saved.occurrence.id)
        val result = repo.onAlarmFired(saved.occurrence.id)
        assertThat(result.notify).isFalse()
    }

    @Test
    fun editParaHorarioPassadoViraNaoRealizada() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(14, 0)))
        repo.editOccurrence(
            saved.occurrence.id,
            "Consulta",
            LocalDate.of(2026, 8, 19),
            LocalTime.of(9, 0),
            RecurrenceRule(),
        )
        val row = occurrenceDao.getAll().single()
        assertThat(row.status).isEqualTo(OccurrenceStatus.MISSED.name)
        assertThat(row.nextReminderAtEpochMs).isNull()
    }

    @Test
    fun deleteCancelaAlarme() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0)))
        repo.deleteOccurrence(saved.occurrence.id)
        assertThat(scheduler.cancelled).contains(saved.occurrence.id)
        assertThat(occurrenceDao.get(saved.occurrence.id)).isNull()
    }

    @Test
    fun desfazerExclusaoReagenda() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Farmácia", LocalDate.of(2026, 8, 22), LocalTime.of(11, 0)))
        val item = AgendaItem(saved.occurrence, saved.series)
        repo.deleteOccurrence(saved.occurrence.id)
        scheduler.scheduled.clear()
        repo.restore(item)
        assertThat(occurrenceDao.get(saved.occurrence.id)).isNotNull()
        assertThat(occurrenceDao.get(saved.occurrence.id)!!.status).isEqualTo(OccurrenceStatus.PENDING.name)
        assertThat(scheduler.scheduled).contains(saved.occurrence.id)
    }

    @Test
    fun completeNaoMexemJaConcluida() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.complete(saved.occurrence.id)
        val first = occurrenceDao.get(saved.occurrence.id)!!
        repo.complete(saved.occurrence.id)
        val second = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(second.completedAtEpochMs).isEqualTo(first.completedAtEpochMs)
        assertThat(second.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
    }

    @Test
    fun completeTambemFechaNaoRealizada() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0)))
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        repo.complete(saved.occurrence.id)
        assertThat(occurrenceDao.get(saved.occurrence.id)!!.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
    }

    @Test
    fun retryMissedComHorarioAbertoFicaHoje() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(14, 0)))
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        scheduler.scheduled.clear()
        val result = repo.retryMissed(saved.occurrence.id)
        assertThat(result).isNotNull()
        assertThat(result!!.date).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(result.time).isEqualTo(LocalTime.of(14, 0))
        assertThat(occurrenceDao.get(saved.occurrence.id)).isNull()
        val fresh = occurrenceDao.getAll().single()
        assertThat(fresh.status).isEqualTo(OccurrenceStatus.PENDING.name)
        assertThat(fresh.localDate).isEqualTo("2026-08-20")
        assertThat(scheduler.scheduled).contains(fresh.id)
    }

    @Test
    fun retryMissedComHorarioPassadoVaiAmanha() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Manhã", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0)))
        val result = repo.retryMissed(saved.occurrence.id)
        assertThat(result!!.date).isEqualTo(LocalDate.of(2026, 8, 21))
        val fresh = occurrenceDao.getAll().single()
        assertThat(fresh.localDate).isEqualTo("2026-08-21")
        assertThat(fresh.status).isEqualTo(OccurrenceStatus.PENDING.name)
    }

    @Test
    fun retryMissedIgnoraRecorrente() = runBlocking {
        val draft = completeDraft("Remédio", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0))
            .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
        val saved = repo.saveDraft(draft)
        val pending = occurrenceDao.getAll().single()
        occurrenceDao.upsert(pending.copy(status = OccurrenceStatus.MISSED.name, missedAtEpochMs = clock.instant().toEpochMilli()))
        val result = repo.retryMissed(pending.id)
        assertThat(result).isNull()
        assertThat(occurrenceDao.get(pending.id)!!.status).isEqualTo(OccurrenceStatus.MISSED.name)
        assertThat(saved.series.recurrence.kind).isEqualTo(RecurrenceKind.DAILY)
    }

    @Test
    fun retryMissedIgnoraPendente() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0)))
        assertThat(repo.retryMissed(saved.occurrence.id)).isNull()
        assertThat(occurrenceDao.get(saved.occurrence.id)!!.status).isEqualTo(OccurrenceStatus.PENDING.name)
    }

    @Test
    fun snoozeDezMinutos() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.snooze(saved.occurrence.id, 10)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        val expected = clock.instant().plusSeconds(10 * 60).toEpochMilli()
        assertThat(row.snoozedUntilEpochMs).isEqualTo(expected)
        assertThat(row.nextReminderAtEpochMs).isEqualTo(expected)
    }

    @Test
    fun desfazerConcluirFuturaReagenda() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0)))
        val item = AgendaItem(saved.occurrence, saved.series)
        repo.complete(saved.occurrence.id)
        scheduler.scheduled.clear()
        repo.uncomplete(item)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(row.status).isEqualTo(OccurrenceStatus.PENDING.name)
        assertThat(scheduler.scheduled).contains(saved.occurrence.id)
    }

    @Test
    fun desfazerConcluirNaoRealizadaVoltaAtrasada() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0)))
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        val item = AgendaItem(saved.occurrence, saved.series)
        repo.complete(saved.occurrence.id)
        scheduler.scheduled.clear()
        repo.uncomplete(item)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(row.status).isEqualTo(OccurrenceStatus.MISSED.name)
        assertThat(scheduler.scheduled).isEmpty()
    }

    private fun completeDraft(title: String, date: LocalDate, time: LocalTime) = ParsedTaskDraft(
        title = title,
        localDate = date,
        localTime = time,
        recurrence = RecurrenceRule(RecurrenceKind.NONE),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = title,
    )
}

private class RecordingScheduler : AlarmScheduler {
    var exact: Boolean = true
    val scheduled = mutableListOf<String>()
    val cancelled = mutableListOf<String>()

    override suspend fun quietHours(): QuietHours = QuietHours()
    override fun canScheduleExact(): Boolean = exact
    override fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean): SchedulerOutcome {
        scheduled += occurrence.id
        return SchedulerOutcome(inexact = !exact, scheduled = true)
    }
    override fun cancel(occurrenceId: String) {
        cancelled += occurrenceId
    }
}

private class FakeSeriesDao : SeriesDao {
    private val rows = linkedMapOf<String, SeriesEntity>()
    private val flow = MutableStateFlow<List<SeriesEntity>>(emptyList())
    private fun emit() { flow.value = rows.values.toList() }
    override suspend fun get(id: String) = rows[id]
    override suspend fun getAll() = rows.values.toList()
    override fun observeAll(): Flow<List<SeriesEntity>> = flow.map { it }
    override suspend fun upsert(entity: SeriesEntity): Long {
        rows[entity.id] = entity
        emit()
        return 1
    }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        emit()
        return if (removed) 1 else 0
    }
}

private class FakeOccurrenceDao : OccurrenceDao {
    private val rows = linkedMapOf<String, OccurrenceEntity>()
    private val flow = MutableStateFlow<List<OccurrenceEntity>>(emptyList())
    private fun emit() { flow.value = rows.values.toList() }
    override suspend fun get(id: String) = rows[id]
    override suspend fun forSeries(seriesId: String) = rows.values.filter { it.seriesId == seriesId }
    override suspend fun byStatus(status: String) = rows.values.filter { it.status == status }
    override suspend fun getAll() = rows.values.toList()
    override fun observeAll(): Flow<List<OccurrenceEntity>> = flow.map { it }
    override suspend fun upsert(entity: OccurrenceEntity): Long {
        rows[entity.id] = entity
        emit()
        return 1
    }
    override suspend fun upsertAll(entities: List<OccurrenceEntity>): List<Long> = entities.map { upsert(it) }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        emit()
        return if (removed) 1 else 0
    }
    override suspend fun deleteSeries(seriesId: String): Int {
        val ids = rows.filterValues { it.seriesId == seriesId }.keys.toList()
        ids.forEach { rows.remove(it) }
        emit()
        return ids.size
    }
}

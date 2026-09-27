package com.theopadilha.falaagenda.ui.month

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.repo.SchedulerOutcome
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MonthSummaryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * O bug: um Flow novo a cada recomposição fazia o banco ser reassinado a cada toque
     * nas setas de mês. Assinar uma vez só não pode depender de quantas vezes a tela
     * recompõe, lê ou coleta o estado.
     */
    @Test
    fun assinaObancoUmaVezMesmoComRecomposicoes() = runTest(dispatcher) {
        val seriesDao = CountingSeriesDao()
        val occurrenceDao = CountingOccurrenceDao()
        val viewModel = MonthSummaryViewModel(
            TaskRepository(seriesDao, occurrenceDao, clock, NoopScheduler),
        )

        val first = launch { viewModel.agenda.collect { } }
        val second = launch { viewModel.agenda.collect { } }
        repeat(3) { viewModel.agenda.value }

        assertThat(seriesDao.observeAllCalls).isEqualTo(1)
        assertThat(occurrenceDao.observeAllCalls).isEqualTo(1)
        first.cancel()
        second.cancel()
    }

    /** O estado do ViewModel é a agenda de verdade, não só o valor vazio inicial. */
    @Test
    fun estadoDoViewModelCarregaAagenda() = runTest(dispatcher) {
        val seriesDao = CountingSeriesDao()
        val occurrenceDao = CountingOccurrenceDao()
        val repository = TaskRepository(seriesDao, occurrenceDao, clock, NoopScheduler)
        // 14h e não 9h: o relógio do teste está às 10h, e o saveDraft marca como não
        // realizada a ocorrência que já passou.
        repository.saveDraft(
            completeDraft("Cabelo", LocalDate.of(2026, 8, 20), LocalTime.of(14, 0)),
        )

        val viewModel = MonthSummaryViewModel(repository)
        val job = launch { viewModel.agenda.collect { } }
        runCurrent()

        assertThat(viewModel.agenda.value.today.map { it.series.title }).containsExactly("Cabelo")
        job.cancel()
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

/** Conta quantas vezes a tela pediu o fluxo do banco: é isso que a assinatura única evita. */
private class CountingSeriesDao : SeriesDao {
    var observeAllCalls = 0
        private set
    private val rows = linkedMapOf<String, SeriesEntity>()
    private val flow = MutableStateFlow<List<SeriesEntity>>(emptyList())

    override suspend fun get(id: String): SeriesEntity? = rows[id]
    override suspend fun getAll(): List<SeriesEntity> = rows.values.toList()
    override fun observeAll(): Flow<List<SeriesEntity>> {
        observeAllCalls++
        return flow.map { it }
    }
    override suspend fun upsert(entity: SeriesEntity) {
        rows[entity.id] = entity
        flow.value = rows.values.toList()
    }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        flow.value = rows.values.toList()
        return if (removed) 1 else 0
    }
}

private class CountingOccurrenceDao : OccurrenceDao {
    var observeAllCalls = 0
        private set
    private val rows = linkedMapOf<String, OccurrenceEntity>()
    private val flow = MutableStateFlow<List<OccurrenceEntity>>(emptyList())

    override suspend fun get(id: String): OccurrenceEntity? = rows[id]
    override suspend fun forSeries(seriesId: String) = rows.values.filter { it.seriesId == seriesId }
    override suspend fun byStatus(status: String) = rows.values.filter { it.status == status }
    override suspend fun getAll(): List<OccurrenceEntity> = rows.values.toList()
    override fun observeAll(): Flow<List<OccurrenceEntity>> {
        observeAllCalls++
        return flow.map { it }
    }
    override suspend fun upsert(entity: OccurrenceEntity): Long {
        rows[entity.id] = entity
        flow.value = rows.values.toList()
        return 1
    }
    override suspend fun upsertAll(entities: List<OccurrenceEntity>): List<Long> =
        entities.map { upsert(it) }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        flow.value = rows.values.toList()
        return if (removed) 1 else 0
    }
    override suspend fun deleteSeries(seriesId: String): Int {
        val ids = rows.filterValues { it.seriesId == seriesId }.keys.toList()
        ids.forEach { rows.remove(it) }
        flow.value = rows.values.toList()
        return ids.size
    }
}

private object NoopScheduler : AlarmScheduler {
    override suspend fun quietHours(): QuietHours = QuietHours()
    override fun canScheduleExact(): Boolean = true
    override fun schedule(
        occurrence: TaskOccurrence,
        series: TaskSeries,
        first: Boolean,
    ): SchedulerOutcome = SchedulerOutcome(inexact = false, scheduled = true)
    override fun cancel(occurrenceId: String) = Unit
}

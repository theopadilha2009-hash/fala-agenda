package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.repo.SchedulerOutcome
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import com.theopadilha.falaagenda.ui.capture.recurrenceFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A promessa da tela e a ocorrência que o salvar cria, conferidas uma contra a outra.
 *
 * Faltava justamente este teste — nenhum comparava as duas pontas — e a ausência explica o
 * defeito ter passado: a tela dizia "Vai avisar terça, 29/09" com a data do seletor e o
 * repositório nascia na primeira segunda (o `RecurrenceEngine.firstOnOrAfter` trata a data
 * escolhida como piso, não como resposta). Eram dois contratos para a mesma promessa, e
 * nenhum dos dois estava escrito.
 *
 * O que se prende aqui: dado o mesmo rascunho (data escolhida, hora e regra — montados como
 * os chips da tela os montam, ver `recurrenceFor`), o texto do resumo tem que citar a data
 * da ocorrência que o repositório gravou. Se a tela voltar a calcular por conta própria,
 * este teste cai.
 */
class PromessaDaTelaBateComOAgendamentoTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    /** Terça-feira, 29 de setembro de 2026, 15:00 — o "hoje" do cenário do chip. */
    private val terca = LocalDate.of(2026, 9, 29)
    private val agora = terca.atTime(15, 0).atZone(zone).toInstant()

    private val scheduler = TestScheduler()

    private fun repo(now: Instant): TaskRepository = TaskRepository(
        FakeSeriesDao(),
        FakeOccurrenceDao(),
        FixedAppClock(now, zone),
        scheduler,
    )

    /**
     * Ela fala "toda segunda natação às 18h" e toca no chip "Hoje" (uma terça). A regra não
     * tem terça: o primeiro aviso é segunda, 05/10. O resumo e o botão têm que dizer isso.
     */
    @Test
    fun chipHojeComRegraSemanalPrometeODiaQueOVAIAvisar() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.WEEKLY, terca, setOf(DayOfWeek.MONDAY))
        val draft = rascunho("Natação", terca, LocalTime.of(18, 0), rule)

        val saved = repo(agora).saveDraft(draft)
        val promessa = promessa(draft, terca, agora)

        assertThat(saved.occurrence.localDate).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(promessa.recap).contains(AgendaFormat.longDate(saved.occurrence.localDate))
        assertThat(promessa.recap).contains("18:00")
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(terca))
        assertThat(promessa.saveLabel).contains(AgendaFormat.dateLabel(saved.occurrence.localDate, terca))
    }

    /**
     * A variante que se contradizia sozinha: "Dias úteis" com uma data de sábado. O resumo
     * dizia "Vai avisar Sábado, 3 de outubro..." e a regra não tem sábado — o alarme era
     * segunda. O par (data real, regra) lado a lado é o que impede a frase de se contradizer.
     */
    @Test
    fun diasUteisEmUmSabadoNaoPrometemOSabado() = runBlocking {
        val sabado = LocalDate.of(2026, 10, 3)
        val agoraDoSabado = sabado.atTime(15, 0).atZone(zone).toInstant()
        val rule = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        val draft = rascunho("Cabelo", sabado, LocalTime.of(9, 0), rule)

        val saved = repo(agoraDoSabado).saveDraft(draft)
        val promessa = promessa(draft, sabado, agoraDoSabado)

        assertThat(saved.occurrence.localDate).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(promessa.recap).contains(AgendaFormat.longDate(saved.occurrence.localDate))
        assertThat(promessa.recap).doesNotContain(AgendaFormat.longDate(sabado))
        assertThat(promessa.recap).contains(rule.describePtBr())
    }

    /**
     * A escolha descartada não pode sumir em silêncio: quando a data que ela tocou não é a
     * data que vai valer, a tela diz isso numa linha — senão o chip "Hoje" some da tela sem
     * explicação e ela não tem como entender nem corrigir.
     */
    @Test
    fun dataDescartadaApareceEmUmaLinha() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.WEEKLY, terca, setOf(DayOfWeek.MONDAY))
        val draft = rascunho("Natação", terca, LocalTime.of(18, 0), rule)

        val promessa = promessa(draft, terca, agora)

        assertThat(promessa.droppedChoice).isNotNull()
        assertThat(promessa.droppedChoice!!).contains(AgendaFormat.longDate(LocalDate.of(2026, 10, 5)))
    }

    /** Quando a data escolhida é a que vale, não há o que explicar. */
    @Test
    fun semDataDescartadaNaoHaOLinhaAMais() {
        val rule = recurrenceFor(RecurrenceKind.WEEKLY, terca, setOf(DayOfWeek.TUESDAY))
        val draft = rascunho("Natação", terca, LocalTime.of(18, 0), rule)

        val promessa = promessa(draft, terca, agora)

        assertThat(promessa.droppedChoice).isNull()
        assertThat(promessa.recap).contains(AgendaFormat.longDate(terca))
    }

    /**
     * Todas as regras, comparadas com a ocorrência que o repositório grava — inclusive as em
     * que a data escolhida já vale, para este teste não passar por acerto de uma regra só.
     */
    @Test
    fun todasAsRegrasConcordamComAOccurrenceGravada() = runBlocking {
        val cenarios = listOf(
            RecurrenceKind.NONE to terca,
            RecurrenceKind.DAILY to terca,
            RecurrenceKind.WEEKDAYS to LocalDate.of(2026, 10, 3),
            RecurrenceKind.WEEKLY to terca,
            RecurrenceKind.MONTHLY to terca,
            RecurrenceKind.YEARLY to terca,
        )

        cenarios.forEach { (kind, escolhida) ->
            val semana = if (kind == RecurrenceKind.WEEKLY) setOf(DayOfWeek.MONDAY) else emptySet()
            val rule = recurrenceFor(kind, escolhida, semana)
            val agoraDoCenario = escolhida.atTime(15, 0).atZone(zone).toInstant()
            val draft = rascunho("Compromisso", escolhida, LocalTime.of(18, 0), rule)

            val saved = repo(agoraDoCenario).saveDraft(draft)
            val promessa = promessa(draft, escolhida, agoraDoCenario)

            assertThat(promessa.recap)
                .contains(AgendaFormat.longDate(saved.occurrence.localDate))
        }
    }

    /**
     * O outro lado da mesma promessa: a escolha que já passou e não repete. Nenhum alarme é
     * criado (a ocorrência é arquivada como não realizada) e o resumo — e o botão — não podem
     * anunciar um aviso que não vai existir. O aplicativo dizia "Vai avisar hoje às 08:00" e,
     * na tela seguinte, mostrava "Não consegui avisar" sobre a mesma tarefa.
     */
    @Test
    fun escolhaQueJaPassouNaoPrometeAviso() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.NONE, terca, emptySet())
        val draft = rascunho("Remédio", terca, LocalTime.of(8, 0), rule)

        val saved = repo(agora).saveDraft(draft)
        val promessa = promessa(draft, terca, agora)

        // O desfecho que o repositório decidiu: arquivada, sem alarme nenhum.
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(scheduler.scheduled).isEmpty()
        // E a promessa diz o mesmo.
        assertThat(promessa.recap).doesNotContain("Vai avisar")
        assertThat(promessa.recap).contains("já passou")
        assertThat(promessa.saveLabel).contains("sem aviso")
    }

    /** O mesmo horário de ontem: passado é passado em qualquer dia, não só "hoje". */
    @Test
    fun escolhaDeOntemTambemNaoPrometeAviso() = runBlocking {
        val ontem = terca.minusDays(1)
        val rule = recurrenceFor(RecurrenceKind.NONE, ontem, emptySet())
        val draft = rascunho("Remédio", ontem, LocalTime.of(8, 0), rule)

        val saved = repo(agora).saveDraft(draft)
        val promessa = promessa(draft, terca, agora)

        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(promessa.recap).doesNotContain("Vai avisar")
        assertThat(promessa.saveLabel).contains("sem aviso")
    }

    /** A regra que repete continua prometendo: o aviso chega, mesmo com a hora já passada. */
    @Test
    fun escolhaPassadaQueRepeteContinuaPrometendo() = runBlocking {
        val rule = recurrenceFor(RecurrenceKind.DAILY, terca, emptySet())
        val draft = rascunho("Remédio", terca, LocalTime.of(8, 0), rule)

        val saved = repo(agora).saveDraft(draft)
        val promessa = promessa(draft, terca, agora)

        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
        assertThat(promessa.recap).startsWith("Vai avisar")
    }

    /** Excluir no passado é o caso que o modo edição já honrava — a tela diz a mesma data. */
    @Test
    fun editandoADataEscolhidaEADataQueVale() = runBlocking {
        val sabado = LocalDate.of(2026, 10, 3)
        val rule = recurrenceFor(RecurrenceKind.WEEKDAYS, sabado, emptySet())
        val draft = rascunho("Cabelo", sabado, LocalTime.of(9, 0), rule)

        val promessa = AgendaFormat.promiseOfChoice(
            chosenDate = draft.localDate!!,
            chosenTime = draft.localTime!!,
            recurrence = draft.recurrence,
            today = sabado,
            now = sabado.atTime(15, 0).atZone(zone).toInstant(),
            zone = zone,
            editing = true,
        )

        assertThat(promessa.recap).contains(AgendaFormat.longDate(sabado))
        assertThat(promessa.droppedChoice).isNull()
    }

    /** O que a tela monta: os mesmos argumentos que a `ConfirmDraftScreen` tem em mãos. */
    private fun promessa(draft: ParsedTaskDraft, today: LocalDate, now: Instant) =
        AgendaFormat.promiseOfChoice(
            chosenDate = draft.localDate!!,
            chosenTime = draft.localTime!!,
            recurrence = draft.recurrence,
            today = today,
            now = now,
            zone = zone,
        )

    private fun rascunho(
        title: String,
        date: LocalDate,
        time: LocalTime,
        rule: RecurrenceRule,
    ) = ParsedTaskDraft(
        title = title,
        localDate = date,
        localTime = time,
        recurrence = rule,
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = title,
    )
}

private class TestScheduler : AlarmScheduler {
    val scheduled = mutableListOf<String>()

    override suspend fun quietHours(): QuietHours = QuietHours()
    override fun canScheduleExact(): Boolean = true
    override fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean): SchedulerOutcome {
        scheduled += occurrence.id
        return SchedulerOutcome(inexact = false, scheduled = true)
    }
    override fun cancel(occurrenceId: String) = Unit
    override fun scheduleRecovery(occurrenceId: String, at: Instant) = Unit
    override fun scheduleDailySweep(at: Instant) = Unit
}

private class FakeSeriesDao : SeriesDao {
    private val rows = linkedMapOf<String, SeriesEntity>()
    private val flow = MutableStateFlow<List<SeriesEntity>>(emptyList())
    private fun emit() { flow.value = rows.values.toList() }
    override suspend fun get(id: String) = rows[id]
    override suspend fun getAll() = rows.values.toList()
    override fun observeAll(): Flow<List<SeriesEntity>> = flow.map { it }
    override suspend fun upsert(entity: SeriesEntity) {
        rows[entity.id] = entity
        emit()
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

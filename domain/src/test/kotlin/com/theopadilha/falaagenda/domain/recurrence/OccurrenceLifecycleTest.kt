package com.theopadilha.falaagenda.domain.recurrence

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.reminder.ReminderPolicy
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class OccurrenceLifecycleTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = Instant.parse("2026-09-27T11:00:00Z")

    private fun series(
        start: LocalDate = LocalDate.of(2026, 9, 20),
        skipped: Set<LocalDate> = emptySet(),
    ) = TaskSeries(
        id = "s1",
        title = "Remédio",
        zoneId = zone,
        localTime = LocalTime.of(8, 0),
        startLocalDate = start,
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        skippedDates = skipped,
        createdAt = Instant.parse("2026-09-20T11:00:00Z"),
        updatedAt = Instant.parse("2026-09-20T11:00:00Z"),
    )

    @Test
    fun advanceMaterializaODiaCorrente() {
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = emptyList(),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.upserts.map { it.localDate }).containsExactly(LocalDate.of(2026, 9, 27))
    }

    @Test
    fun advanceNaoRematerializaDiaExcluido() {
        val change = OccurrenceLifecycle.advance(
            series = series(skipped = setOf(LocalDate.of(2026, 9, 27))),
            existing = emptyList(),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.upserts).isEmpty()
        assertThat(change.cancelAlarmsOf).isEmpty()
    }

    @Test
    fun advanceVoltaAMaterializarNoDiaSeguinte() {
        val change = OccurrenceLifecycle.advance(
            series = series(skipped = setOf(LocalDate.of(2026, 9, 27))),
            existing = emptyList(),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 28),
        )
        assertThat(change.upserts.map { it.localDate }).containsExactly(LocalDate.of(2026, 9, 28))
    }

    /**
     * Lembrete adiado pelo horário de silêncio só toca às 08:00 do dia seguinte. A
     * varredura não pode dar a ocorrência de ontem como não realizada enquanto o
     * aviso dela ainda está marcado para tocar.
     */
    @Test
    fun ocorrenciaDeOntemComLembreteFuturoContinuaPendente() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(ontem, nextReminderAt = now.plusSeconds(3 * 3600))
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed).isEmpty()
        assertThat(change.cancelAlarmsOf).isEmpty()
    }

    /** O lembrete pode estar marcado no próprio instante da varredura: ainda vai tocar. */
    @Test
    fun ocorrenciaDeOntemComLembreteNoInstanteAtualContinuaPendente() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(ontem, nextReminderAt = now)
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed).isEmpty()
    }

    /** Adiamento é ação explícita do usuário e vale a mesma proteção do lembrete. */
    @Test
    fun ocorrenciaDeOntemComSnoozeNoFuturoContinuaPendente() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(ontem, nextReminderAt = null, snoozedUntil = now.plusSeconds(600))
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed).isEmpty()
        assertThat(change.cancelAlarmsOf).isEmpty()
    }

    /** Sem nada marcado para tocar, a data vencida vira não realizada como antes. */
    @Test
    fun ocorrenciaDeOntemSemLembreteFuturoViraNaoRealizada() {
        val ontem = LocalDate.of(2026, 9, 26)
        val vencida = occurrence(ontem, nextReminderAt = now.minusSeconds(3600))
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(vencida),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed.map { it.id }).containsExactly(vencida.id)
        assertThat(change.markMissed.single().status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(change.cancelAlarmsOf).containsExactly(vencida.id)
    }

    private fun occurrence(
        date: LocalDate,
        nextReminderAt: Instant?,
        snoozedUntil: Instant? = null,
    ) = TaskOccurrence(
        id = OccurrenceIds.of("s1", date),
        seriesId = "s1",
        localDate = date,
        scheduledAt = date.atTime(8, 0).atZone(zone).toInstant(),
        status = OccurrenceStatus.PENDING,
        reminderStep = ReminderPolicy.STEP_HOURLY,
        nextReminderAt = nextReminderAt,
        snoozedUntil = snoozedUntil,
    )

    @Test
    fun skipDateGuardaADataEDescartaAsMuitoAntigas() {
        val antiga = LocalDate.of(2026, 1, 10)
        val recente = LocalDate.of(2026, 9, 20)
        val hoje = LocalDate.of(2026, 9, 27)
        val depois = OccurrenceLifecycle.skipDate(setOf(antiga, recente), hoje, hoje)
        assertThat(depois).containsExactly(recente, hoje)
    }

    @Test
    fun skipDateTemTetoDeDatas() {
        val hoje = LocalDate.of(2026, 9, 27)
        val muitas = (0 until 200).map { hoje.minusDays(it.toLong()) }.toSet()
        val depois = OccurrenceLifecycle.skipDate(muitas, hoje, hoje)
        assertThat(depois).contains(hoje)
        assertThat(depois.size).isAtMost(OccurrenceLifecycle.MAX_SKIPPED_DATES)
        val limite = hoje.minusDays(OccurrenceLifecycle.SKIPPED_RETENTION_DAYS)
        assertThat(depois.any { it.isBefore(limite) }).isFalse()
    }

    @Test
    fun unskipDateDevolveAData() {
        val hoje = LocalDate.of(2026, 9, 27)
        val depois = OccurrenceLifecycle.unskipDate(setOf(hoje, hoje.minusDays(1)), hoje)
        assertThat(depois).containsExactly(hoje.minusDays(1))
    }
}

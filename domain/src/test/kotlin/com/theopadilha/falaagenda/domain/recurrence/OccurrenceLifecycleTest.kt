package com.theopadilha.falaagenda.domain.recurrence

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskSeries
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

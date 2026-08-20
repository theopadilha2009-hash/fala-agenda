package com.theopadilha.falaagenda.domain.recurrence

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskSeries
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RecurrenceEngineTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun mesCurtoClampDia31() {
        val date = RecurrenceEngine.clampToValidDate(2026, 2, 31)
        assertThat(date).isEqualTo(LocalDate.of(2026, 2, 28))
        val april = RecurrenceEngine.clampToValidDate(2026, 4, 31)
        assertThat(april).isEqualTo(LocalDate.of(2026, 4, 30))
    }

    @Test
    fun fevereiro29AnoNaoBissexto() {
        assertThat(RecurrenceEngine.yearlyDate(2026, 2, 29)).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(RecurrenceEngine.yearlyDate(2028, 2, 29)).isEqualTo(LocalDate.of(2028, 2, 29))
    }

    @Test
    fun mensalDia31AtravessaFevereiro() {
        val rule = RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = 31)
        val start = LocalDate.of(2026, 1, 31)
        val next = RecurrenceEngine.nextAfter(rule, start, start)
        assertThat(next).isEqualTo(LocalDate.of(2026, 2, 28))
        val march = RecurrenceEngine.nextAfter(rule, start, next!!)
        assertThat(march).isEqualTo(LocalDate.of(2026, 3, 31))
    }

    @Test
    fun multiplosDiasSemanais() {
        val rule = RecurrenceRule(
            RecurrenceKind.WEEKLY,
            weekDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        )
        val start = LocalDate.of(2026, 8, 20) // quinta
        val dates = RecurrenceEngine.upcoming(rule, start, start, 4)
        assertThat(dates).containsExactly(
            LocalDate.of(2026, 8, 24),
            LocalDate.of(2026, 8, 26),
            LocalDate.of(2026, 8, 31),
            LocalDate.of(2026, 9, 2),
        ).inOrder()
    }

    @Test
    fun pendenteViraNaoRealizadaQuandoProximaNasce() {
        val series = TaskSeries(
            id = "s1",
            title = "Vitamina",
            zoneId = zone,
            localTime = LocalTime.of(8, 0),
            startLocalDate = LocalDate.of(2026, 8, 20),
            recurrence = RecurrenceRule(RecurrenceKind.DAILY),
            createdAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val first = OccurrenceLifecycle.materialize(
            series,
            LocalDate.of(2026, 8, 20),
            java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val change = OccurrenceLifecycle.advance(
            series = series,
            existing = listOf(first),
            now = java.time.Instant.parse("2026-08-21T12:00:00Z"),
            todayInSeriesZone = LocalDate.of(2026, 8, 21),
        )
        assertThat(change.markMissed).hasSize(1)
        assertThat(change.markMissed.first().status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(change.cancelAlarmsOf).contains(first.id)
        assertThat(change.upserts.any { it.localDate == LocalDate.of(2026, 8, 21) && it.status == OccurrenceStatus.PENDING }).isTrue()
    }

    @Test
    fun unicaAtrasadaViraNaoRealizadaNoAdvance() {
        val series = TaskSeries(
            id = "u1",
            title = "Única",
            zoneId = zone,
            localTime = LocalTime.of(9, 0),
            startLocalDate = LocalDate.of(2026, 8, 20),
            recurrence = RecurrenceRule(),
            createdAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val first = OccurrenceLifecycle.materialize(
            series,
            LocalDate.of(2026, 8, 20),
            java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val change = OccurrenceLifecycle.advance(
            series = series,
            existing = listOf(first),
            now = java.time.Instant.parse("2026-08-21T12:00:00Z"),
            todayInSeriesZone = LocalDate.of(2026, 8, 21),
        )
        assertThat(change.markMissed).hasSize(1)
        assertThat(change.markMissed.first().status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(change.cancelAlarmsOf).contains(first.id)
        assertThat(change.upserts.none { it.status == OccurrenceStatus.PENDING }).isTrue()
    }

    @Test
    fun unicaDeHojePermanecePendente() {
        val series = TaskSeries(
            id = "u2",
            title = "Única",
            zoneId = zone,
            localTime = LocalTime.of(9, 0),
            startLocalDate = LocalDate.of(2026, 8, 20),
            recurrence = RecurrenceRule(),
            createdAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val first = OccurrenceLifecycle.materialize(
            series,
            LocalDate.of(2026, 8, 20),
            java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val change = OccurrenceLifecycle.advance(
            series = series,
            existing = listOf(first),
            now = java.time.Instant.parse("2026-08-20T14:00:00Z"),
            todayInSeriesZone = LocalDate.of(2026, 8, 20),
        )
        assertThat(change.markMissed).isEmpty()
    }

    @Test
    fun firstOnOrAfterNoneNaoDevolveDataPassada() {
        val none = RecurrenceRule()
        val start = LocalDate.of(2026, 8, 20)
        assertThat(RecurrenceEngine.firstOnOrAfter(none, start, start)).isEqualTo(start)
        assertThat(RecurrenceEngine.firstOnOrAfter(none, start, start.plusDays(1))).isNull()
        assertThat(RecurrenceEngine.firstOnOrAfter(none, start, start.minusDays(1))).isEqualTo(start)
    }

    @Test
    fun materializeNaoResetaSnoozeNemEscada() {
        val series = TaskSeries(
            id = "s1",
            title = "Vitamina",
            zoneId = zone,
            localTime = LocalTime.of(8, 0),
            startLocalDate = LocalDate.of(2026, 8, 20),
            recurrence = RecurrenceRule(RecurrenceKind.DAILY),
            createdAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val first = OccurrenceLifecycle.materialize(
            series,
            LocalDate.of(2026, 8, 20),
            java.time.Instant.parse("2026-08-20T11:00:00Z"),
        )
        val snoozedUntil = java.time.Instant.parse("2026-08-20T12:30:00Z")
        val snoozed = first.copy(
            reminderStep = 3,
            lastReminderAt = java.time.Instant.parse("2026-08-20T12:00:00Z"),
            snoozedUntil = snoozedUntil,
            nextReminderAt = snoozedUntil,
        )
        val refreshed = OccurrenceLifecycle.materialize(
            series,
            LocalDate.of(2026, 8, 20),
            java.time.Instant.parse("2026-08-20T12:05:00Z"),
            snoozed,
        )
        assertThat(refreshed.nextReminderAt).isEqualTo(snoozedUntil)
        assertThat(refreshed.reminderStep).isEqualTo(3)
        assertThat(refreshed.snoozedUntil).isEqualTo(snoozedUntil)
    }

    @Test
    fun horarioDeVeraoGapNaoQuebraInstante() {
        val ny = ZoneId.of("America/New_York")
        val series = TaskSeries(
            id = "dst",
            title = "DST",
            zoneId = ny,
            localTime = LocalTime.of(2, 30),
            startLocalDate = LocalDate.of(2026, 3, 8),
            recurrence = RecurrenceRule(),
            createdAt = java.time.Instant.parse("2026-03-07T12:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-03-07T12:00:00Z"),
        )
        val instant = OccurrenceLifecycle.scheduledInstant(series, LocalDate.of(2026, 3, 8))
        val zoned = instant.atZone(ny)
        assertThat(zoned.toLocalTime()).isGreaterThan(LocalTime.of(2, 0))
        assertThat(zoned.offset).isEqualTo(java.time.ZoneOffset.ofHours(-4))
    }

    @Test
    fun horarioDeVeraoOverlapUsaOffsetAnterior() {
        val ny = ZoneId.of("America/New_York")
        val series = TaskSeries(
            id = "fall",
            title = "DST",
            zoneId = ny,
            localTime = LocalTime.of(1, 30),
            startLocalDate = LocalDate.of(2026, 11, 1),
            recurrence = RecurrenceRule(),
            createdAt = java.time.Instant.parse("2026-10-31T12:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-10-31T12:00:00Z"),
        )
        val instant = OccurrenceLifecycle.scheduledInstant(series, LocalDate.of(2026, 11, 1))
        assertThat(instant.atZone(ny).offset).isEqualTo(java.time.ZoneOffset.ofHours(-4))
    }

    @Test
    fun fusoDaSerieNaoMudaComRelogioDoAparelho() {
        val sp = ZoneId.of("America/Sao_Paulo")
        val series = TaskSeries(
            id = "s",
            title = "Local",
            zoneId = sp,
            localTime = LocalTime.of(9, 0),
            startLocalDate = LocalDate.of(2026, 8, 20),
            recurrence = RecurrenceRule(),
            createdAt = java.time.Instant.parse("2026-08-20T12:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-08-20T12:00:00Z"),
        )
        val inSp = OccurrenceLifecycle.scheduledInstant(series, LocalDate.of(2026, 8, 20))
        val viewedInNy = inSp.atZone(ZoneId.of("America/New_York"))
        assertThat(inSp.atZone(sp).toLocalTime()).isEqualTo(LocalTime.of(9, 0))
        assertThat(viewedInNy.toLocalTime()).isNotEqualTo(LocalTime.of(9, 0))
    }
}

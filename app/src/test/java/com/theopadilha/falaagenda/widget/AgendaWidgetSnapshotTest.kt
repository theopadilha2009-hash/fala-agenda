package com.theopadilha.falaagenda.widget

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AgendaWidgetSnapshotTest {
    private val today = LocalDate.of(2026, 8, 21)
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun vazioQuandoNaoHaPendente() {
        val snap = AgendaWidgetProvider.snapshotOf(
            AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
            today,
        )
        assertThat(snap.empty).isTrue()
        assertThat(snap.title).isEqualTo("Nada marcado")
    }

    @Test
    fun mostraProximaDeHoje() {
        val item = item("Cabelo", today, LocalTime.of(15, 0))
        val snap = AgendaWidgetProvider.snapshotOf(
            AgendaSections(listOf(item), emptyList(), emptyList(), emptyList()),
            today,
        )
        assertThat(snap.empty).isFalse()
        assertThat(snap.title).isEqualTo("Cabelo")
        assertThat(snap.whenLabel).contains("15:00")
        assertThat(snap.whenLabel).contains("Hoje")
    }

    private fun item(title: String, date: LocalDate, time: LocalTime): AgendaItem {
        val series = TaskSeries(
            id = "s1",
            title = title,
            zoneId = zone,
            localTime = time,
            startLocalDate = date,
            recurrence = RecurrenceRule(),
            createdAt = Instant.parse("2026-08-20T12:00:00Z"),
            updatedAt = Instant.parse("2026-08-20T12:00:00Z"),
        )
        val occ = TaskOccurrence(
            id = "s1:$date",
            seriesId = series.id,
            localDate = date,
            scheduledAt = date.atTime(time).atZone(zone).toInstant(),
            status = OccurrenceStatus.PENDING,
        )
        return AgendaItem(occ, series)
    }
}

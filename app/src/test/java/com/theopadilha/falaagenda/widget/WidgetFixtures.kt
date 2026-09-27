package com.theopadilha.falaagenda.widget

import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal val HOJE: LocalDate = LocalDate.of(2026, 8, 21)

private val FUSO: ZoneId = ZoneId.of("America/Sao_Paulo")
private val CRIACAO: Instant = Instant.parse("2026-08-20T12:00:00Z")

internal fun agendaCom(
    titulo: String,
    data: LocalDate = HOJE,
    hora: LocalTime = LocalTime.of(8, 0),
): AgendaSections {
    val series = TaskSeries(
        id = "s1",
        title = titulo,
        zoneId = FUSO,
        localTime = hora,
        startLocalDate = data,
        recurrence = RecurrenceRule(),
        createdAt = CRIACAO,
        updatedAt = CRIACAO,
    )
    val ocorrencia = TaskOccurrence(
        id = "s1:$data",
        seriesId = series.id,
        localDate = data,
        scheduledAt = data.atTime(hora).atZone(FUSO).toInstant(),
        status = OccurrenceStatus.PENDING,
    )
    return AgendaSections(
        today = listOf(AgendaItem(ocorrencia, series)),
        upcoming = emptyList(),
        completed = emptyList(),
        missed = emptyList(),
    )
}

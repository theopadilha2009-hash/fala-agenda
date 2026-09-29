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

    /** Meio-dia do dia de referência: tudo o que é de hoje de manhã já passou. */
    private val meioDia: Instant = today.atTime(12, 0).atZone(zone).toInstant()

    @Test
    fun vazioQuandoNaoHaPendente() {
        val snap = AgendaWidgetProvider.snapshotOf(
            AgendaSections(emptyList(), emptyList(), emptyList(), emptyList()),
            today,
            meioDia,
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
            meioDia,
        )
        assertThat(snap.empty).isFalse()
        assertThat(snap.title).isEqualTo("Cabelo")
        assertThat(snap.whenLabel).contains("15:00")
        assertThat(snap.whenLabel).contains("Hoje")
        assertThat(snap.late).isFalse()
    }

    /**
     * `sectionsOf` põe de propósito a pendente que atravessou a meia-noite dentro de "Hoje" —
     * ela é a mais urgente e continua acionável. O widget, porém, dizia "Próxima" para ela: o
     * `minByOrNull` elegia a mais antiga, e o cartão anunciava "Próxima — Tomar remédio —
     * Ontem · 08:00" na janela da manhã em que ela olha o telefone para planejar o dia.
     */
    @Test
    fun naoAnunciaAAtrasadaDeOntemComoProxima() {
        val ontem = item("Tomar remédio", today.minusDays(1), LocalTime.of(8, 0))
        val hoje = item("Cabelo", today, LocalTime.of(15, 0))

        val snap = AgendaWidgetProvider.snapshotOf(
            AgendaSections(listOf(ontem, hoje), emptyList(), emptyList(), emptyList()),
            today,
            meioDia,
        )

        assertThat(snap.title).isEqualTo("Cabelo")
        assertThat(snap.late).isFalse()
    }

    /**
     * O outro lado da mesma linha: às 15:00 a ocorrência das 08:00 de hoje já passou, e o
     * widget a anunciava sob "Próxima — Hoje · 08:00" — a tela mentindo sobre o que vai
     * acontecer, com a próxima de verdade logo depois.
     */
    @Test
    fun naoAnunciaComoProximaAAtrasadaDeHoje() {
        val oito = item("Remédio", today, LocalTime.of(8, 0))
        val dezoito = item("Jantar", today, LocalTime.of(18, 0))
        val tresDaTarde = today.atTime(15, 0).atZone(zone).toInstant()

        val snap = AgendaWidgetProvider.snapshotOf(
            AgendaSections(listOf(oito, dezoito), emptyList(), emptyList(), emptyList()),
            today,
            tresDaTarde,
        )

        assertThat(snap.title).isEqualTo("Jantar")
        assertThat(snap.late).isFalse()
    }

    /** Sem nada à frente, o que sobrou é o que já passou — e o widget diz "Atrasada". */
    @Test
    fun atrasadaQuandoNaoHaNadaAFrente() {
        val oito = item("Remédio", today, LocalTime.of(8, 0))
        val tresDaTarde = today.atTime(15, 0).atZone(zone).toInstant()

        val snap = AgendaWidgetProvider.snapshotOf(
            AgendaSections(listOf(oito), emptyList(), emptyList(), emptyList()),
            today,
            tresDaTarde,
        )

        assertThat(snap.title).isEqualTo("Remédio")
        assertThat(snap.late).isTrue()
    }

    /** A pendente de ontem sem nada à frente também é atrasada, não "Próxima". */
    @Test
    fun atrasadaDeOntemSemNadaAFrente() {
        val ontem = item("Tomar remédio", today.minusDays(1), LocalTime.of(8, 0))

        val snap = AgendaWidgetProvider.snapshotOf(
            AgendaSections(listOf(ontem), emptyList(), emptyList(), emptyList()),
            today,
            meioDia,
        )

        assertThat(snap.late).isTrue()
        assertThat(snap.whenLabel).contains("Ontem")
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

package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AgendaFormatTest {
    private val today = LocalDate.of(2026, 8, 20)

    @Test
    fun rotulosRelativos() {
        assertThat(AgendaFormat.dateLabel(today, today)).isEqualTo("Hoje")
        assertThat(AgendaFormat.dateLabel(today.plusDays(1), today)).isEqualTo("Amanhã")
        assertThat(AgendaFormat.dateLabel(today.minusDays(1), today)).isEqualTo("Ontem")
        assertThat(AgendaFormat.dateLabel(LocalDate.of(2026, 8, 25), today)).isEqualTo("25/08")
    }

    @Test
    fun dataPorExtensoEmPortugues() {
        val text = AgendaFormat.longDate(today)
        assertThat(text.lowercase()).contains("agosto")
        assertThat(text).contains("2026")
        assertThat(text).contains("20")
    }

    @Test
    fun horario24h() {
        assertThat(AgendaFormat.time(LocalTime.of(9, 5))).isEqualTo("09:05")
        assertThat(AgendaFormat.time(LocalTime.of(21, 0))).isEqualTo("21:00")
    }
}

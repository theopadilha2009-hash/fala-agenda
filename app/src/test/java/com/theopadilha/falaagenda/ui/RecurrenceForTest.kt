package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.ui.capture.recurrenceFor
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RecurrenceForTest {
    private val thursday = LocalDate.of(2026, 8, 20)

    @Test
    fun semanalUsaDiasEscolhidos() {
        val rule = recurrenceFor(
            RecurrenceKind.WEEKLY,
            thursday,
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        )
        assertThat(rule.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(rule.weekDays).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
    }

    @Test
    fun semanalVazioCaiNoDiaDaData() {
        val rule = recurrenceFor(RecurrenceKind.WEEKLY, thursday, emptySet())
        assertThat(rule.weekDays).containsExactly(DayOfWeek.THURSDAY)
    }

    @Test
    fun mensalUsaDiaDaData() {
        val rule = recurrenceFor(RecurrenceKind.MONTHLY, thursday, emptySet())
        assertThat(rule.dayOfMonth).isEqualTo(20)
    }

    @Test
    fun anualUsaDiaEMes() {
        val rule = recurrenceFor(RecurrenceKind.YEARLY, thursday, emptySet())
        assertThat(rule.dayOfMonth).isEqualTo(20)
        assertThat(rule.monthOfYear).isEqualTo(8)
    }
}

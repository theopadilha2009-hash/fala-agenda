package com.theopadilha.falaagenda.domain.insight

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MonthInsightsTest {
    private val august = YearMonth.of(2026, 8)

    @Test
    fun tresCabelosViramMaisFrequente() {
        val rows = listOf(
            row("Cabelo", 4),
            row("cabelo", 11),
            row("Cabelo", 18),
            row("Farmácia", 20),
        )
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.completed).isEqualTo(4)
        assertThat(insight.frequent.first().title).isEqualTo("Cabelo")
        assertThat(insight.frequent.first().times).isEqualTo(3)
    }

    @Test
    fun pendenteTambemContaComoMarcado() {
        val rows = listOf(
            row("Cabelo", 4),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 25), OccurrenceStatus.PENDING, null),
        )
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.completed).isEqualTo(1)
        assertThat(insight.frequent.first().times).isEqualTo(2)
    }

    @Test
    fun somaGastosDoMes() {
        val rows = listOf(
            row("Cabelo", 4, 8000),
            row("Cabelo", 18, 8000),
            row("Farmácia", 20, 2500),
        )
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.spentCents).isEqualTo(18500)
        assertThat(insight.spentLabel()).contains("185")
    }

    @Test
    fun ignoraOutroMes() {
        val rows = listOf(
            row("Cabelo", 4),
            InsightRow("Cabelo", LocalDate.of(2026, 7, 30), OccurrenceStatus.COMPLETED, null),
        )
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.completed).isEqualTo(1)
    }

    @Test
    fun parseReais() {
        assertThat(Money.parseReais("80")).isEqualTo(8000)
        assertThat(Money.parseReais("R$ 80,50")).isEqualTo(8050)
        assertThat(Money.parseReais("80.50")).isEqualTo(8050)
        assertThat(Money.parseReais("80.5")).isEqualTo(8050)
        assertThat(Money.parseReais("1.080,50")).isEqualTo(108050)
        assertThat(Money.parseReais("")).isNull()
    }

    private fun row(title: String, day: Int, cents: Long? = null) = InsightRow(
        title = title,
        date = LocalDate.of(2026, 8, day),
        status = OccurrenceStatus.COMPLETED,
        amountCents = cents,
    )
}

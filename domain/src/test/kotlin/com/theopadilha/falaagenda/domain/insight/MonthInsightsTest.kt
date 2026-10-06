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
    fun pendenteNaoContaComoFeito() {
        val rows = listOf(
            row("Cabelo", 4),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 25), OccurrenceStatus.PENDING, null),
        )
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.completed).isEqualTo(1)
        assertThat(insight.frequent.single().times).isEqualTo(1)
    }

    @Test
    fun naoRealizadaNaoEntraEmOQueMaisVoceFez() {
        // "Cabelo" diário: 1 feita + 5 não realizadas + 1 futura ainda pendente
        val rows = listOf(
            row("Cabelo", 4),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 5), OccurrenceStatus.MISSED, null),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 6), OccurrenceStatus.MISSED, null),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 7), OccurrenceStatus.MISSED, null),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 8), OccurrenceStatus.MISSED, null),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 9), OccurrenceStatus.MISSED, null),
            InsightRow("Farmácia", LocalDate.of(2026, 8, 28), OccurrenceStatus.PENDING, null),
        )
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.frequent.map { it.title }).containsExactly("Cabelo")
        assertThat(insight.frequent.single().times).isEqualTo(1)
        assertThat(insight.completed).isEqualTo(1)
        assertThat(insight.missed).isEqualTo(5)
    }

    @Test
    fun gastoSoSomaOQueFoiConcluido() {
        val rows = listOf(
            row("Cabelo", 4, 8000),
            InsightRow("Cabelo", LocalDate.of(2026, 8, 5), OccurrenceStatus.MISSED, 5000),
            InsightRow("Farmácia", LocalDate.of(2026, 8, 28), OccurrenceStatus.PENDING, 2500),
        )
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.spentCents).isEqualTo(8000)
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

    /**
     * Uma ocorrência pode ficar para trás por dois motivos, e o resumo só contava um. A que
     * nasceu vencida — criada para um horário que já passou — nunca teve aviso
     * (`lastReminderAt` nulo): quem falhou foi o aplicativo, e o resumo não pode cobrar dela
     * essa falta. É a mesma separação que a home já faz em "Não consegui avisar".
     */
    @Test
    fun naoRealizadaSemAvisoNaoEntraComoFaltaDela() {
        val rows = listOf(
            semAviso("Tomar remédio", 1),
            comAviso("Caminhada", 2),
        )
        val insight = MonthInsights.of(rows, august)
        // O total continua sendo o total: nada some da contagem.
        assertThat(insight.missed).isEqualTo(2)
        assertThat(insight.naoAvisadas).isEqualTo(1)
        assertThat(insight.naoRealizadas).isEqualTo(1)
        assertThat(insight.naoAvisadasLabel()).isEqualTo("Não consegui avisar 1 tarefa")
    }

    /** Só falhas do app: nenhuma não realizada é dela. */
    @Test
    fun aFalhaDoAppNaoSobraComoNaoRealizada() {
        val rows = listOf(semAviso("Tomar remédio", 1), semAviso("Água", 3))
        val insight = MonthInsights.of(rows, august)
        assertThat(insight.missed).isEqualTo(2)
        assertThat(insight.naoRealizadas).isEqualTo(0)
        assertThat(insight.naoAvisadasLabel()).isEqualTo("Não consegui avisar 2 tarefas")
    }

    /** Sem falha do app não há o que assumir: "0 avisos" não se lê. */
    @Test
    fun semFalhaDoAppORotuloDaFalhaFicaVazio() {
        val insight = MonthInsights.of(listOf(comAviso("Caminhada", 2)), august)
        assertThat(insight.naoAvisadas).isEqualTo(0)
        assertThat(insight.naoAvisadasLabel()).isEmpty()
        assertThat(insight.naoRealizadas).isEqualTo(1)
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

    /** Ficou para trás sem nenhum aviso ter saído: falha do app. */
    private fun semAviso(title: String, day: Int) = InsightRow(
        title = title,
        date = LocalDate.of(2026, 8, day),
        status = OccurrenceStatus.MISSED,
        naoAvisada = true,
    )

    /** Ficou para trás com o aviso entregue: falta dela. */
    private fun comAviso(title: String, day: Int) = InsightRow(
        title = title,
        date = LocalDate.of(2026, 8, day),
        status = OccurrenceStatus.MISSED,
        naoAvisada = false,
    )
}

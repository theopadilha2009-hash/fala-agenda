package com.theopadilha.falaagenda.ui.month

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.insight.MonthInsights
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * O recap da home e o resumo do mês liam o mês inteiro pelo `missed` e diziam "não realizadas"
 * sobre tudo — inclusive a tarefa que o próprio aplicativo nunca avisou (`lastReminderAt`
 * nulo, o caso da tarefa criada para um horário já vencido). A home, na mesma tela, já dizia
 * "Não consegui avisar" sobre ela. É a manchete do topo que já foi corrigida: o mesmo defeito
 * sobrevivia aqui, onde ela lê o fechamento do mês.
 *
 * `insightRows()` é o único ponto onde a informação do aviso podia se perder no caminho até a
 * camada de insights: se o `lastReminderAt` não viaja da ocorrência para o `InsightRow`, a
 * camada não tem como separar os dois motivos, por mais correta que a decisão esteja lá.
 */
class MonthRecapTextTest {
    private val agosto = YearMonth.of(2026, 8)
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val aviso: Instant = Instant.parse("2026-08-19T12:00:00Z")

    /**
     * A propagação do dado: a ocorrência sem aviso sai daqui marcada como falha do app, e a
     * com aviso não. Sem esta passagem, a camada de insights separa por um campo que nunca
     * chega preenchido.
     */
    @Test
    fun aLinhaDoInsightLevaOAvisoDaOcorrencia() {
        val sections = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(
                missed("Tomar remédio", LocalDate.of(2026, 8, 1), lastReminderAt = null),
                missed("Caminhada", LocalDate.of(2026, 8, 2), lastReminderAt = aviso),
            ),
        )

        val rows = sections.insightRows()

        assertThat(rows.single { it.title == "Tomar remédio" }.naoAvisada).isTrue()
        assertThat(rows.single { it.title == "Caminhada" }.naoAvisada).isFalse()
    }

    /**
     * O cenário concreto: no dia 1, a tarefa criada para um horário já vencido nasce MISSED e
     * sem aviso. O recap não pode contar as duas como "não realizadas" dela.
     */
    @Test
    fun oRecapSeparaAFalhaDoAppDaFaltaDela() {
        val sections = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = listOf(concluida("Farmácia", LocalDate.of(2026, 8, 3))),
            missed = listOf(
                missed("Tomar remédio", LocalDate.of(2026, 8, 1), lastReminderAt = null),
                missed("Caminhada", LocalDate.of(2026, 8, 2), lastReminderAt = aviso),
            ),
        )
        val insight = MonthInsights.of(sections.insightRows(), agosto)

        val linha = monthRecapLine(insight)

        assertThat(linha).contains("1 feitas")
        assertThat(linha).contains("1 não realizadas")
        assertThat(linha).contains("Não consegui avisar 1 tarefa")
        // A remédio sem aviso não entra como falta dela: o "1 não realizadas" é só da caminhada.
        assertThat(linha).doesNotContain("2 não realizadas")
    }

    /** Sem falha do app a linha fica como sempre foi: nada de "0 avisos". */
    @Test
    fun semFalhaDoAppARecapNaoInventaORotulo() {
        val sections = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = listOf(concluida("Farmácia", LocalDate.of(2026, 8, 3))),
            missed = listOf(missed("Caminhada", LocalDate.of(2026, 8, 2), lastReminderAt = aviso)),
        )

        val linha = monthRecapLine(MonthInsights.of(sections.insightRows(), agosto))

        assertThat(linha).isEqualTo("1 feitas · 1 não realizadas")
        assertThat(linha).doesNotContain("avisar")
    }

    /** Só falhas do app: o recap assume o que foi do aplicativo, sem sobrar falta dela. */
    @Test
    fun quandoSoAFalhaDoAppORecapNaoDizQueElaDeixouDeFazer() {
        val sections = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(missed("Tomar remédio", LocalDate.of(2026, 8, 1), lastReminderAt = null)),
        )

        val linha = monthRecapLine(MonthInsights.of(sections.insightRows(), agosto))

        assertThat(linha).contains("Não consegui avisar 1 tarefa")
        assertThat(linha).doesNotContain("não realizadas")
    }

    /**
     * As linhas de "faltas" do resumo do mês, montadas fora do composable: é o que dá ao teste
     * algo a que se agarrar. Inline, um revert de `naoRealizadas` para `missed` passaria com a
     * suíte verde e o defeito voltaria em silêncio.
     */
    @Test
    fun aLinhaDoMesSeparaAFalhaDoAppDaFaltaDela() {
        val sections = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(
                missed("Tomar remédio", LocalDate.of(2026, 8, 1), lastReminderAt = null),
                missed("Caminhada", LocalDate.of(2026, 8, 2), lastReminderAt = aviso),
            ),
        )

        val linhas = monthMissedLines(MonthInsights.of(sections.insightRows(), agosto))

        assertThat(linhas).containsExactly("1 não realizadas", "Não consegui avisar 1 tarefa").inOrder()
    }

    /** Só a falha do app: nada sobra como falta dela, e nenhuma linha é "0 não realizadas". */
    @Test
    fun oMesComSoFalhaDoAppNaoDizQueElaDeixouDeFazer() {
        val sections = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(missed("Tomar remédio", LocalDate.of(2026, 8, 1), lastReminderAt = null)),
        )

        val linhas = monthMissedLines(MonthInsights.of(sections.insightRows(), agosto))

        assertThat(linhas).containsExactly("Não consegui avisar 1 tarefa")
        assertThat(linhas).doesNotContain("0 não realizadas")
    }

    /** Só falta dela: nenhuma linha de falha do app aparece. */
    @Test
    fun oMesSemFalhaDoAppNaoInventaORotulo() {
        val sections = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(missed("Caminhada", LocalDate.of(2026, 8, 2), lastReminderAt = aviso)),
        )

        val linhas = monthMissedLines(MonthInsights.of(sections.insightRows(), agosto))

        assertThat(linhas).containsExactly("1 não realizadas")
    }

    private fun missed(title: String, date: LocalDate, lastReminderAt: Instant?) = item(
        title = title,
        date = date,
        status = OccurrenceStatus.MISSED,
        lastReminderAt = lastReminderAt,
    )

    private fun concluida(title: String, date: LocalDate) = item(
        title = title,
        date = date,
        status = OccurrenceStatus.COMPLETED,
        lastReminderAt = null,
    )

    private fun item(
        title: String,
        date: LocalDate,
        status: OccurrenceStatus,
        lastReminderAt: Instant?,
    ): AgendaItem {
        val serie = TaskSeries(
            id = "s-$title",
            title = title,
            zoneId = zone,
            localTime = LocalTime.of(8, 0),
            startLocalDate = date,
            recurrence = RecurrenceRule(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
        return AgendaItem(
            occurrence = TaskOccurrence(
                id = "${serie.id}:$date",
                seriesId = serie.id,
                localDate = date,
                scheduledAt = date.atTime(LocalTime.of(8, 0)).atZone(zone).toInstant(),
                status = status,
                lastReminderAt = lastReminderAt,
            ),
            series = serie,
        )
    }
}

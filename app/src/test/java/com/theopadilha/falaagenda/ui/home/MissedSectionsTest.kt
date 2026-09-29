package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A ocorrência que ficou para trás conta duas histórias diferentes, e a tela contava só uma.
 *
 * Com o aviso entregue, a falta é dela e "Não realizadas" diz a verdade. Sem nenhum aviso
 * entregue (`lastReminderAt` nulo — avisos desligados, canal bloqueado, permissão negada, ou
 * a tarefa marcada para uma hora que já passou), quem falhou foi o aplicativo: a mesma seção
 * acusava a usuária do remédio que o aplicativo não avisou.
 */
class MissedSectionsTest {

    private val hoje = LocalDate.of(2026, 8, 21)
    private val fuso: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val criacao: Instant = Instant.parse("2026-08-20T12:00:00Z")

    private fun ficouParaTras(titulo: String, ultimoAviso: Instant?) = AgendaItem(
        occurrence = TaskOccurrence(
            id = "$titulo:$hoje",
            seriesId = titulo,
            localDate = hoje.minusDays(1),
            scheduledAt = hoje.minusDays(1).atTime(LocalTime.of(8, 0)).atZone(fuso).toInstant(),
            status = OccurrenceStatus.MISSED,
            missedAt = hoje.atTime(LocalTime.of(0, 5)).atZone(fuso).toInstant(),
            lastReminderAt = ultimoAviso,
        ),
        series = TaskSeries(
            id = titulo,
            title = titulo,
            zoneId = fuso,
            localTime = LocalTime.of(8, 0),
            startLocalDate = hoje.minusDays(1),
            recurrence = RecurrenceRule(),
            createdAt = criacao,
            updatedAt = criacao,
        ),
    )

    private fun titulos(sections: List<MissedSection>) = sections.map { it.title }

    /** O aviso tocou e ela não fez: é falta dela, e continua em "Não realizadas". */
    @Test
    fun avisoEntregueElaNaoFezContinuaEmNaoRealizadas() {
        val caminhada = ficouParaTras("Caminhada", ultimoAviso = criacao)

        val secoes = missedSections(listOf(caminhada))

        assertThat(titulos(secoes)).containsExactly("Não realizadas")
        assertThat(secoes.single().items.map { it.occurrence.id }).containsExactly(caminhada.occurrence.id)
        // A linha do aviso entregue não muda: nenhuma nota diz que o aplicativo falhou.
        assertThat(secoes.single().note).isNull()
    }

    /**
     * Nenhum aviso jamais saiu: o aplicativo não conseguiu avisar, e a seção diz isso — não
     * que ela deixou de fazer.
     */
    @Test
    fun semNenhumAvisoASecaoDizQueOAplicativoNaoConseguiuAvisar() {
        val remedio = ficouParaTras("Tomar remédio", ultimoAviso = null)

        val secoes = missedSections(listOf(remedio))

        assertThat(titulos(secoes)).containsExactly("Não consegui avisar")
        assertThat(secoes.single().items.map { it.occurrence.id }).containsExactly(remedio.occurrence.id)
        assertThat(secoes.single().title).isNotEqualTo("Não realizadas")
    }

    /** Ela lê a linha do cartão: o aviso que não saiu precisa estar escrito nela. */
    @Test
    fun aLinhaDoCartaoDizQueOAvisoNaoTocou() {
        val remedio = ficouParaTras("Tomar remédio", ultimoAviso = null)
        val caminhada = ficouParaTras("Caminhada", ultimoAviso = criacao)

        val semAviso = missedSections(listOf(remedio)).single()
        val comAviso = missedSections(listOf(caminhada)).single()

        assertThat(semAviso.note).isNotNull()
        assertThat(semAviso.note!!.lowercase()).contains("aviso")
        assertThat(semAviso.note!!.lowercase()).contains("não tocou")
        assertThat(comAviso.note).isNull()
    }

    /**
     * Os dois casos dividem a seção sem que nenhuma ocorrência fique fora: a que ficou para
     * trás sem aviso sumia da agenda se a divisão fosse um filtro.
     */
    @Test
    fun osDoisCasosSeDividemSemDeixarNinguemFora() {
        val semAviso = ficouParaTras("Tomar remédio", ultimoAviso = null)
        val comAviso = ficouParaTras("Caminhada", ultimoAviso = criacao)

        val secoes = missedSections(listOf(semAviso, comAviso))

        assertThat(secoes.flatMap { it.items }.map { it.occurrence.id })
            .containsExactly(semAviso.occurrence.id, comAviso.occurrence.id)
    }

    /** Seção sem nada dentro não aparece na home. */
    @Test
    fun secaoVaziaNaoAparece() {
        val comAviso = ficouParaTras("Caminhada", ultimoAviso = criacao)

        assertThat(titulos(missedSections(listOf(comAviso)))).containsExactly("Não realizadas")
        assertThat(titulos(missedSections(emptyList()))).isEmpty()

        val soSemAviso = missedSections(listOf(ficouParaTras("Tomar remédio", ultimoAviso = null)))
        assertThat(soSemAviso).hasSize(1)
        assertThat(soSemAviso.single().title).isEqualTo("Não consegui avisar")
    }

    /** A falha do aplicativo vem primeiro: é o que ela precisa saber antes de se achar culpada. */
    @Test
    fun aFalhaDoAplicativoVemAntesDaFalhaDela() {
        val semAviso = ficouParaTras("Tomar remédio", ultimoAviso = null)
        val comAviso = ficouParaTras("Caminhada", ultimoAviso = criacao)

        assertThat(titulos(missedSections(listOf(comAviso, semAviso))))
            .containsExactly("Não consegui avisar", "Não realizadas")
            .inOrder()
    }
}

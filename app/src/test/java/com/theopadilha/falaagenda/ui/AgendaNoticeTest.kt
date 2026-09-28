package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.ui.home.AgendaUi
import com.theopadilha.falaagenda.ui.home.initialAgendaUi
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * O toque no aviso do remédio com a leitura da agenda falhando era o pior dos silêncios: o
 * alarme tocava, ela tocava, e nada acontecia — nem a tarefa abria, nem uma frase dizia por
 * quê. O id ficava pendente para o resto da vida do processo.
 *
 * O root não tem teste de renderização, então o que se prova aqui é a decisão que o efeito do
 * `FalaAgendaRoot` toma a partir do estado: qual dos três desfechos sai, e — o que importa
 * para o silêncio — que a falha na leitura nunca sai como "esta tarefa não está mais na
 * agenda".
 */
class AgendaNoticeTest {
    private val vazia = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun semLeituraAindaNaoSeConcluiNada() {
        assertThat(agendaNotice(initialAgendaUi, "s1:2026-08-20")).isEqualTo(AgendaNotice.Unreadable)
    }

    /**
     * A falha antes de qualquer lista publica uma agenda vazia: sem olhar o `failed`, esta é
     * a hora em que o app diz que a tarefa saiu da agenda — para uma agenda que ele não leu.
     */
    @Test
    fun aFalhaAntesDeQualquerListaNaoEhTarefaQueSaiuDaAgenda() {
        val falhou = initialAgendaUi.copy(loaded = true, failed = true)

        assertThat(agendaNotice(falhou, "s1:2026-08-20")).isEqualTo(AgendaNotice.Unreadable)
    }

    /**
     * A releitura que falha deixa na tela a última lista boa (ver `agendaUiFrom`): uma tarefa
     * que entrou no banco depois dela não está nessa lista, e não é por isso que ela saiu da
     * agenda.
     */
    @Test
    fun aReleituraQueFalhaNaoAutorizaDizerQueElaSaiuDaAgenda() {
        val outra = item("s2:2026-08-21")
        val releituraFalhou = AgendaUi(
            sections = AgendaSections(listOf(outra), emptyList(), emptyList(), emptyList()),
            loaded = true,
            failed = true,
        )

        assertThat(agendaNotice(releituraFalhou, "s1:2026-08-20")).isEqualTo(AgendaNotice.Unreadable)
    }

    /** O que está na última lista boa abre normalmente, mesmo com a releitura falhando. */
    @Test
    fun achouNaUltimaListaBoaAbre() {
        val remedio = item("s1:2026-08-20")
        val releituraFalhou = AgendaUi(
            sections = AgendaSections(listOf(remedio), emptyList(), emptyList(), emptyList()),
            loaded = true,
            failed = true,
        )

        assertThat(agendaNotice(releituraFalhou, remedio.occurrence.id))
            .isEqualTo(AgendaNotice.Open(remedio))
    }

    /** A leitura respondeu e a tarefa não está nela: aí sim, ela saiu da agenda. */
    @Test
    fun leituraBoaSemATarefaEhTarefaQueSaiuDaAgenda() {
        val leu = AgendaUi(sections = vazia, loaded = true, failed = false)

        assertThat(agendaNotice(leu, "s1:2026-08-20")).isEqualTo(AgendaNotice.Gone)
    }

    private fun item(occurrenceId: String): AgendaItem {
        val serie = TaskSeries(
            id = "s1",
            title = "Tomar remédio",
            zoneId = ZoneId.of("America/Sao_Paulo"),
            localTime = LocalTime.of(8, 0),
            startLocalDate = LocalDate.of(2026, 8, 20),
            recurrence = RecurrenceRule(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
        return AgendaItem(
            occurrence = TaskOccurrence(
                id = occurrenceId,
                seriesId = serie.id,
                localDate = LocalDate.of(2026, 8, 20),
                scheduledAt = Instant.EPOCH,
                status = OccurrenceStatus.PENDING,
            ),
            series = serie,
        )
    }
}

package com.theopadilha.falaagenda.ui.home

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

/**
 * O convite a falar ("Pode falar: tomar remédio amanhã às 8h") saía da mesma pergunta que o
 * "Nada para hoje": lista vazia, e nada mais. Com a leitura da agenda falhando, a home dizia
 * as duas coisas na mesma tela — o cartão "Não consegui ler a sua agenda" em cima e o
 * convite logo abaixo — e o convite é justamente o que leva ela a recadastrar o que já
 * existe: segunda série, segundo alarme. É o mesmo dano que o commit suprimiu do texto
 * irmão, e a lista vazia da falha não é lista vazia: é lista que não foi lida.
 *
 * O root e a home não têm teste de renderização nesta suíte, então o que se prova aqui é a
 * decisão que o `LazyColumn` toma: com a leitura falhando o convite não aparece, e sem
 * falha ele continua aparecendo para a agenda vazia.
 */
class HomeSpeakInviteTest {
    private val vazia = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun comALeituraFalhandoOConviteNaoAparece() {
        assertThat(showsSpeakInvite(vazia, failed = true)).isFalse()
    }

    @Test
    fun agendaVaziaLidaSemFalhaConvida() {
        assertThat(showsSpeakInvite(vazia, failed = false)).isTrue()
    }

    /** O convite continua sendo sobre "não há nada", e não sobre "não deu para ler". */
    @Test
    fun comTarefaDeHojeNaoConvida() {
        val comTarefa = AgendaSections(listOf(itemDeHoje()), emptyList(), emptyList(), emptyList())

        assertThat(showsSpeakInvite(comTarefa, failed = false)).isFalse()
    }

    @Test
    fun comProximaTarefaNaoConvida() {
        val comTarefa = AgendaSections(emptyList(), listOf(itemDeHoje()), emptyList(), emptyList())

        assertThat(showsSpeakInvite(comTarefa, failed = false)).isFalse()
    }

    private fun itemDeHoje(): AgendaItem {
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
                id = "s1:2026-08-20",
                seriesId = serie.id,
                localDate = LocalDate.of(2026, 8, 20),
                scheduledAt = Instant.EPOCH,
                status = OccurrenceStatus.PENDING,
            ),
            series = serie,
        )
    }
}

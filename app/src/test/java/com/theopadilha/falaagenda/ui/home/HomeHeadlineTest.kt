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
 * A manchete do topo era a única parte da home que não olhava o `failed`: com a leitura da
 * agenda falhando e sem dado velho, ela dizia "Boa noite. Nada marcado agora." três linhas
 * acima do cartão "Não consegui ler a sua agenda" — e é `heading()` na semântica, o marco
 * por onde o TalkBack navega. O convite a falar já tinha teste próprio pelo mesmo motivo (ver
 * [HomeSpeakInviteTest]): a fiação da flag não pode passar batido só porque o composable não
 * tem teste de renderização aqui.
 *
 * O que se prova é a decisão que o `Text` do cabeçalho toma: com a leitura falhando e sem
 * próximo a frase de ausência sai de cena; com próximo ela fica, porque a falha carrega o
 * dado da última leitura boa.
 */
class HomeHeadlineTest {
    private val hoje = LocalDate.of(2026, 8, 20)
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val vazia = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun comALeituraFalhandoESemProximoAMancheteNaoAfirmaAusencia() {
        val text = homeHeadline(
            agenda = vazia,
            failed = true,
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Não consegui ler a sua agenda agora.")
        assertThat(text).doesNotContain("Nada marcado agora")
    }

    /**
     * Este é o caso que impede o conserto de virar "esconder a agenda toda quando a rede
     * pisca": havendo próximo, ele continua na manchete.
     */
    @Test
    fun comALeituraFalhandoEComProximoAMancheteMantemOProximo() {
        val comTarefa = AgendaSections(
            today = listOf(item("Tomar remédio", hoje, LocalTime.of(8, 0), Instant.EPOCH)),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = emptyList(),
        )

        val text = homeHeadline(
            agenda = comTarefa,
            failed = true,
            nowTime = LocalTime.of(8, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Bom dia. Próximo: Tomar remédio, hoje às 08:00.")
        assertThat(text).doesNotContain("Não consegui ler")
    }

    @Test
    fun semFalhaESemProximoAMancheteContinuaONadaMarcado() {
        val text = homeHeadline(
            agenda = vazia,
            failed = false,
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Nada marcado agora.")
    }

    /**
     * A montagem da manchete saiu do composable inteira, inclusive a escolha do compromisso:
     * ela olha "Hoje" e "Amanhã" juntos e fica com o mais próximo dos dois, não com o primeiro
     * da lista.
     */
    @Test
    fun aMancheteAnunciaOCompromissoMaisProximoDosDoisBlocos() {
        val agenda = AgendaSections(
            today = listOf(item("Tomar remédio", hoje, LocalTime.of(9, 0), Instant.EPOCH.plusSeconds(9 * 3600))),
            upcoming = listOf(
                item("Consulta", hoje.plusDays(1), LocalTime.of(8, 0), Instant.EPOCH.plusSeconds(32 * 3600)),
            ),
            completed = emptyList(),
            missed = emptyList(),
        )

        val text = homeHeadline(
            agenda = agenda,
            failed = false,
            nowTime = LocalTime.of(8, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Bom dia. Próximo: Tomar remédio, hoje às 09:00.")
    }

    /** Os 2 recados ficam para trás, e o contador da manchete é o deles. */
    @Test
    fun aMancheteContaOsRecadosQueFicaramParaTras() {
        val agenda = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(item("Remédio", hoje, LocalTime.of(8, 0), Instant.EPOCH)),
        )

        val text = homeHeadline(
            agenda = agenda,
            failed = false,
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Nada marcado agora. 1 recado ficou para trás.")
    }

    private fun item(
        title: String,
        localDate: LocalDate,
        localTime: LocalTime,
        scheduledAt: Instant,
    ): AgendaItem {
        val serie = TaskSeries(
            id = "s-$title",
            title = title,
            zoneId = zone,
            localTime = localTime,
            startLocalDate = localDate,
            recurrence = RecurrenceRule(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )
        return AgendaItem(
            occurrence = TaskOccurrence(
                id = "${serie.id}:$localDate",
                seriesId = serie.id,
                localDate = localDate,
                scheduledAt = scheduledAt,
                status = OccurrenceStatus.PENDING,
            ),
            series = serie,
        )
    }
}

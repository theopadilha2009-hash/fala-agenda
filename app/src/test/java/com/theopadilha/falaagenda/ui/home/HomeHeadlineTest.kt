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

    /** O estado como o composable o recebe: seções e bandeira no mesmo objeto, de propósito. */
    private fun agendaUi(sections: AgendaSections, failed: Boolean) =
        AgendaUi(sections = sections, loaded = true, failed = failed)

    @Test
    fun comALeituraFalhandoESemProximoAMancheteNaoAfirmaAusencia() {
        val text = homeHeadline(
            agendaUi = agendaUi(vazia, failed = true),
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
            agendaUi = agendaUi(comTarefa, failed = true),
            nowTime = LocalTime.of(8, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Bom dia. Próximo: Tomar remédio, hoje às 08:00.")
        assertThat(text).doesNotContain("Não consegui ler")
    }

    @Test
    fun semFalhaESemProximoAMancheteContinuaONadaMarcado() {
        val text = homeHeadline(
            agendaUi = agendaUi(vazia, failed = false),
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Nada marcado agora.")
    }

    /**
     * A montagem da manchete saiu do composable inteira, inclusive a escolha do compromisso:
     * ela olha "Hoje" e "Amanhã" juntos e fica com o **mais próximo** dos dois blocos.
     *
     * Os itens de "Hoje" vêm fora de ordem de propósito. O repositório entrega as seções já
     * ordenadas (`TaskRepository.sectionsOf`), então com a lista ordenada `minByOrNull` e
     * `firstOrNull` devolvem o mesmo item e o teste não discrimina nada: passaria verde com a
     * escolha errada dentro de `homeHeadline`. Assim, quem pegar o primeiro da lista anuncia
     * o Cabelo das 15:00 quando o mais próximo é o remédio das 09:00.
     */
    @Test
    fun aMancheteAnunciaOCompromissoMaisProximoDosDoisBlocos() {
        val agenda = AgendaSections(
            today = listOf(
                item("Cabelo", hoje, LocalTime.of(15, 0), Instant.EPOCH.plusSeconds(15 * 3600)),
                item("Tomar remédio", hoje, LocalTime.of(9, 0), Instant.EPOCH.plusSeconds(9 * 3600)),
            ),
            upcoming = listOf(
                item("Consulta", hoje.plusDays(1), LocalTime.of(8, 0), Instant.EPOCH.plusSeconds(32 * 3600)),
            ),
            completed = emptyList(),
            missed = emptyList(),
        )

        val text = homeHeadline(
            agendaUi = agendaUi(agenda, failed = false),
            nowTime = LocalTime.of(8, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Bom dia. Próximo: Tomar remédio, hoje às 09:00.")
    }

    /** O recado fica para trás, e o contador da manchete é o dele. */
    @Test
    fun aMancheteContaOsRecadosQueFicaramParaTras() {
        val agenda = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(item("Remédio", hoje, LocalTime.of(8, 0), Instant.EPOCH)),
        )

        val text = homeHeadline(
            agendaUi = agendaUi(agenda, failed = false),
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Nada marcado agora. 1 recado ficou para trás.")
    }

    /**
     * O contador não sobrevive à frase de falha: ao lado de "não consegui ler" ele diria que
     * algo foi lido ("...agora. 2 recados ficaram para trás."). As seções têm de trazer o
     * `missed` cheio para este teste valer alguma coisa — com a agenda vazia o contador já é
     * zero por fora e a supressão passaria sem ser exercida.
     */
    @Test
    fun comALeituraFalhandoOContadorDeRecadosNaoAparece() {
        val comRecados = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(
                item("Remédio", hoje.minusDays(1), LocalTime.of(8, 0), Instant.EPOCH),
                item("Consulta", hoje.minusDays(2), LocalTime.of(8, 0), Instant.EPOCH),
            ),
        )

        val text = homeHeadline(
            agendaUi = agendaUi(comRecados, failed = true),
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Não consegui ler a sua agenda agora.")
        assertThat(text).doesNotContain("recado")
    }

    /**
     * O que o aplicativo deixou de avisar não pode virar "recado que ficou para trás" na
     * manchete: aquela frase põe a falha nas costas dela, no lugar mais visível da tela, e a
     * seção logo abaixo já diz "Não consegui avisar" assumindo a culpa. O app assume o mesmo
     * no topo.
     */
    @Test
    fun oQueOAppNaoAvisouANaoViraRecadoQueElaDeixouParaTras() {
        val naoAvisados = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(
                item("Remédio", hoje.minusDays(1), LocalTime.of(8, 0), Instant.EPOCH, avisada = false),
            ),
        )

        val text = homeHeadline(
            agendaUi = agendaUi(naoAvisados, failed = false),
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Nada marcado agora. Não consegui avisar 1 tarefa.")
        assertThat(text).doesNotContain("ficou para trás")
    }

    /**
     * Os dois motivos convivem, e cada um conta uma vez só. Sem a subtração, a mesma
     * ocorrência entraria nas duas frases ("1 recado ficou para trás. Não consegui avisar 1
     * tarefa" sobre um remédio só); sem o contador, o que ela não fez sumiria da manchete.
     */
    @Test
    fun osDoisMotivosNaoContamDuasVezesAMesmaOcorrencia() {
        val misturado = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(
                item("Remédio", hoje.minusDays(1), LocalTime.of(8, 0), Instant.EPOCH, avisada = false),
                item("Consulta", hoje.minusDays(2), LocalTime.of(9, 0), Instant.EPOCH, avisada = true),
            ),
        )

        val text = homeHeadline(
            agendaUi = agendaUi(misturado, failed = false),
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo(
            "Boa noite. Nada marcado agora. 1 recado ficou para trás. Não consegui avisar 1 tarefa.",
        )
    }

    /** Só o que ela deixou de fazer: nenhuma menção a falha do app quando não houve. */
    @Test
    fun semFalhaDoAppAMancheteNaoFalaEmNaoConseguirAvisar() {
        val soEla = AgendaSections(
            today = emptyList(),
            upcoming = emptyList(),
            completed = emptyList(),
            missed = listOf(
                item("Consulta", hoje.minusDays(2), LocalTime.of(9, 0), Instant.EPOCH, avisada = true),
            ),
        )

        val text = homeHeadline(
            agendaUi = agendaUi(soEla, failed = false),
            nowTime = LocalTime.of(19, 0),
            today = hoje,
        )

        assertThat(text).isEqualTo("Boa noite. Nada marcado agora. 1 recado ficou para trás.")
        assertThat(text).doesNotContain("Não consegui avisar")
    }

    /**
     * [avisada] diz qual dos dois motivos a ocorrência carrega, porque a manchete agora os
     * separa: `lastReminderAt == null` é "o aplicativo não avisou" (a seção "Não consegui
     * avisar" da home), e preenchido é "avisou e ela não fez" (a seção "Não realizadas").
     * O default é o segundo — um teste que quer o primeiro passa `avisada = false`.
     */
    private fun item(
        title: String,
        localDate: LocalDate,
        localTime: LocalTime,
        scheduledAt: Instant,
        avisada: Boolean = true,
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
                lastReminderAt = if (avisada) Instant.EPOCH else null,
            ),
            series = serie,
        )
    }
}

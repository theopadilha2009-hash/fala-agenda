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
 * Com a tela de edição aberta e a leitura da agenda falhando, não há lista de onde tirar o
 * que a tarefa tem hoje — e seguir para a confirmação com `editing = false` transformava
 * "Editar tarefa" em criação: segunda série com o mesmo título e o mesmo horário, e um
 * segundo alarme. Quem decide isso é este predicado de três valores, e trocar um `&&` por
 * um `||` (ou "simplificar" para `agendaUi.failed`) volta ao segundo alarme sem acender
 * nada. Ele é a outra metade do [agendaNotice] — que ganhou cinco testes no mesmo commit —,
 * e até agora só existia em prosa.
 *
 * O caso que o `failed` sozinho não resolve é o primeiro: com a última lista boa na tela, o
 * item está lá, a bandeira diz "esta é a última lista que consegui ler", e a edição segue
 * normalmente.
 */
class EditingUnavailableTest {
    private val vazia = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList())

    /** Nada para editar (a tarefa nova): a falha na leitura não bloqueia nada. */
    @Test
    fun semItemParaEditarNaoEhFalha() {
        val falhou = initialAgendaUi.copy(loaded = true, failed = true)

        assertThat(editingUnavailable(falhou, editingItemId = null, editingItem = null)).isFalse()
    }

    /** O item está na última lista boa: a leitura falhou depois, e o que ela editou existe. */
    @Test
    fun itemNaUltimaListaBoaNaoEhFalha() {
        val remedio = item("s1:2026-08-20")
        val releituraFalhou = AgendaUi(
            sections = AgendaSections(listOf(remedio), emptyList(), emptyList(), emptyList()),
            loaded = true,
            failed = true,
        )

        assertThat(editingUnavailable(releituraFalhou, remedio.occurrence.id, remedio)).isFalse()
    }

    /** O item não está na lista e a leitura falhou: não dá para abrir a edição. */
    @Test
    fun itemAusenteComALeituraFalhandoEhFalha() {
        val falhou = AgendaUi(sections = vazia, loaded = true, failed = true)

        assertThat(editingUnavailable(falhou, "s1:2026-08-20", null)).isTrue()
    }

    /**
     * O item não está na lista e a leitura respondeu: a tarefa saiu da agenda de verdade.
     * Comportamento pré-existente — quem diz isso a ela é o caminho de criação, e não a
     * tela de falha de leitura.
     */
    @Test
    fun itemAusenteComALeituraBoaNaoEhFalha() {
        val leu = AgendaUi(sections = vazia, loaded = true, failed = false)

        assertThat(editingUnavailable(leu, "s1:2026-08-20", null)).isFalse()
    }

    /** Antes da primeira leitura nada se conclui: a tela espera o banco, não bloqueia. */
    @Test
    fun semLeituraAindaNaoEhFalha() {
        assertThat(editingUnavailable(initialAgendaUi, "s1:2026-08-20", null)).isFalse()
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

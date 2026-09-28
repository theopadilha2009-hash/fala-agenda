package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * O `loaded` é o que a tela de confirmação espera para decidir entre editar e criar
 * (ver `FalaAgendaRoot.awaitingEditingItem`). Um fluxo que estoura sem emitir deixava o
 * `loaded` falso para sempre: a tela só tinha o indicador de carregamento, sem botão e sem
 * saída. Ler o banco é uma tarefa que pode falhar; falhar tem que virar estado utilizável.
 */
class AgendaUiFailureTest {
    private val vazia = AgendaSections(emptyList(), emptyList(), emptyList(), emptyList())

    @Test
    fun falhaAntesDaPrimeiraEmissaoEntregaAgendaCarregada() = runBlocking {
        val quebrado = flow<AgendaSections> { throw IllegalStateException("o banco não abriu") }

        val estado = withTimeout(5_000) { agendaUiFrom(quebrado).first() }

        assertThat(estado.loaded).isTrue()
        assertThat(estado.sections).isEqualTo(vazia)
    }

    /**
     * A falha que vem depois de a agenda já ter chegado não pode apagar o que está na tela:
     * é essa lista que a tela de confirmação lê para saber que a tarefa editada existe. Com
     * uma agenda vazia ali dentro, "Editar tarefa" virava "tarefa nova" — segunda série com
     * o mesmo título e o mesmo horário, e um segundo alarme.
     */
    @Test
    fun falhaDepoisDaPrimeiraEmissaoNaoApagaAAgendaQueJaVeio() = runBlocking {
        val cheia = AgendaSections(listOf(itemDeHoje("s1:2026-08-20")), emptyList(), emptyList(), emptyList())
        val fonte = MutableStateFlow(cheia)
        val caiu = CompletableDeferred<Unit>()
        val escopo = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val estado = agendaUiFrom(
                fonte
                    .map { sections -> if (sections === cheia) sections else error("a leitura caiu") }
                    .onCompletion { caiu.complete(Unit) },
            ).stateIn(escopo, SharingStarted.Eagerly, initialAgendaUi)

            withTimeout(5_000) { estado.first { it.loaded } }
            fonte.value = vazia
            withTimeout(5_000) { caiu.await() }

            assertThat(estado.value.loaded).isTrue()
            assertThat(estado.value.sections).isEqualTo(cheia)
        } finally {
            escopo.cancel()
        }
    }

    private fun itemDeHoje(occurrenceId: String): AgendaItem {
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

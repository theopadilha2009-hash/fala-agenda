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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

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
     * A falha antes de qualquer emissão não pode sair como "li a agenda e ela está vazia":
     * as duas são iguais em conteúdo, e quem decide por elas lê `failed`. Com a vazia no
     * lugar da falha, a tela de edição vira criação (segunda série, segundo alarme) e o
     * efeito do aviso anuncia "Esta tarefa não está mais na agenda" para uma agenda que não
     * foi lida.
     */
    @Test
    fun falhaAntesDaPrimeiraEmissaoNaoEhLeituraDeAgendaVazia() = runBlocking {
        val quebrado = flow<AgendaSections> { throw IllegalStateException("o banco não abriu") }

        val estado = withTimeout(5_000) { agendaUiFrom(quebrado).first() }

        assertThat(estado.failed).isTrue()
    }

    /**
     * "Falhou antes de emitir" é sobre a coleta que está acontecendo, e não sobre o processo:
     * o `stateIn` reinicia a coleta do mesmo fluxo quando a última assinatura sai e outra
     * volta, e uma bandeira guardada fora da cadeia fazia a segunda falha não virar estado
     * nenhum — a tela ficava com a lista da leitura anterior como se a leitura tivesse
     * funcionado.
     */
    @Test
    fun aFalhaEhDaColetaENaoDoProcesso() = runBlocking {
        val cheia = AgendaSections(listOf(itemDeHoje("s1:2026-08-20")), emptyList(), emptyList(), emptyList())
        val tentativas = AtomicInteger()
        val fonte = flow<AgendaSections> {
            if (tentativas.incrementAndGet() == 1) {
                emit(cheia)
                throw IllegalStateException("a leitura caiu depois da lista")
            }
            throw IllegalStateException("o banco não abriu")
        }
        val agenda = agendaUiFrom(fonte)

        // Primeira assinatura: a lista chegou e depois a leitura caiu — a lista fica.
        val primeira = agenda.first { it.loaded }
        assertThat(primeira.sections).isEqualTo(cheia)
        assertThat(primeira.failed).isFalse()

        // A segunda coleta (a home voltou para a tela, o `stateIn` reiniciou o fluxo) cai
        // antes de emitir: é falha, e não uma lista válida.
        val segunda = withTimeout(5_000) { agenda.toList() }

        assertThat(segunda).hasSize(1)
        assertThat(segunda.single().loaded).isTrue()
        assertThat(segunda.single().failed).isTrue()
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
            assertThat(estado.value.failed).isFalse()
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

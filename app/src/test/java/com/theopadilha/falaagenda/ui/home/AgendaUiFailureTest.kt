package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.AgendaSections
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
@OptIn(ExperimentalCoroutinesApi::class)
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
        // antes de emitir: é falha, e não uma lista válida. A coleta não termina mais no
        // `catch` — ela reassina (ver `aLeituraQueFalhaNaoEhPermanente`), então o que se
        // espera aqui é o estado, não o fim do fluxo.
        val segunda = withTimeout(5_000) { agenda.first { it.failed } }

        assertThat(segunda.loaded).isTrue()
        assertThat(segunda.failed).isTrue()
        // E a lista da leitura anterior não é apagada por esta coleta nova que já cai: a
        // memória atravessa o reinício da coleta (ver `LastGoodAgenda`).
        assertThat(segunda.sections).isEqualTo(cheia)
    }

    /**
     * A falha que vem depois de a agenda já ter chegado não pode apagar o que está na tela:
     * é essa lista que a tela de confirmação lê para saber que a tarefa editada existe. Com
     * uma agenda vazia ali dentro, "Editar tarefa" virava "tarefa nova" — segunda série com
     * o mesmo título e o mesmo horário, e um segundo alarme.
     *
     * O `failed` sai verdadeiro: ele diz "esta é a última lista que consegui ler", e é a home
     * que anuncia isso (o "Nada para hoje" some). Quem decide por presença na lista continua
     * com a lista boa na mão.
     */
    @Test
    fun falhaDepoisDaPrimeiraEmissaoNaoApagaAAgendaQueJaVeio() = runBlocking {
        val cheia = AgendaSections(listOf(itemDeHoje("s1:2026-08-20")), emptyList(), emptyList(), emptyList())
        val fonte = MutableStateFlow(cheia)
        val escopo = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val estado = agendaUiFrom(
                fonte.map { sections -> if (sections === cheia) sections else error("a leitura caiu") },
            ).stateIn(escopo, SharingStarted.Eagerly, initialAgendaUi)

            withTimeout(5_000) { estado.first { it.loaded } }
            fonte.value = vazia
            val falhou = withTimeout(5_000) { estado.first { it.failed } }

            assertThat(falhou.loaded).isTrue()
            assertThat(falhou.sections).isEqualTo(cheia)
        } finally {
            escopo.cancel()
        }
    }

    /**
     * A falha não pode ser permanente: o `catch` fechava a coleta, o `observeAll()` do Room
     * desassina no `finally` quando a coleta morre, e nenhuma gravação ressuscitava o fluxo.
     * O `failed` ficava grudado pelo resto da vida do processo — a tarefa impossível de abrir
     * até matar o app. Quem devolve a lista é a releitura.
     */
    @Test
    fun aLeituraQueFalhaNaoEhPermanente() = runBlocking {
        val cheia = AgendaSections(listOf(itemDeHoje("s1:2026-08-20")), emptyList(), emptyList(), emptyList())
        val tentativas = AtomicInteger()
        val fonte = flow<AgendaSections> {
            if (tentativas.incrementAndGet() == 1) throw IllegalStateException("o banco não abriu")
            emit(cheia)
        }

        val estados = withTimeout(5_000) { agendaUiFrom(fonte, retryDelayMs = 1).take(2).toList() }

        // A falha sai na tela — ela precisa saber —, e a releitura traz a lista de volta.
        assertThat(estados.map { it.failed }).containsExactly(true, false).inOrder()
        assertThat(estados.last().loaded).isTrue()
        assertThat(estados.last().sections).isEqualTo(cheia)
    }

    /**
     * A releitura que falha não pode esvaziar o que está na tela: é essa lista que a tela de
     * confirmação lê para saber que a tarefa editada existe. Com a agenda vazia ali dentro,
     * "Editar tarefa" vira "tarefa nova" — segunda série, segundo alarme — e o rascunho que
     * ela está digitando é desmontado.
     */
    @Test
    fun aReleituraQueFalhaPreservaAListaBoa() {
        runBlocking {
            val cheia = AgendaSections(listOf(itemDeHoje("s1:2026-08-20")), emptyList(), emptyList(), emptyList())
            val tentativas = AtomicInteger()
            val fonte = flow<AgendaSections> {
                if (tentativas.incrementAndGet() == 1) {
                    emit(cheia)
                    throw IllegalStateException("a leitura caiu depois da lista")
                }
                throw IllegalStateException("o banco continua fora")
            }

            val estados = withTimeout(5_000) { agendaUiFrom(fonte, retryDelayMs = 1).take(3).toList() }

            assertThat(estados.map { it.failed }).containsExactly(false, true, true).inOrder()
            assertThat(estados.drop(1).map { it.sections }).containsExactly(cheia, cheia)
        }
    }

    /**
     * A fonte que sempre falha é reassinada **com espera**: sem o intervalo, a releitura vira
     * um laço quente que queima bateria em vez de esperar o disco voltar. O widget da agenda
     * tem esta mesma prova (`bancoQuebradoParaSempreNaoDerrubaNemGiraSemEsperar`).
     */
    @Test
    fun aFonteQueSempreFalhaEhTentadaDeNovoComEspera() = runTest {
        val tentativas = AtomicInteger()
        val fonte = flow<AgendaSections> {
            tentativas.incrementAndGet()
            throw IllegalStateException("disco cheio")
        }

        val job = launch { agendaUiFrom(fonte, retryDelayMs = 5_000).collect { } }
        runCurrent()
        assertThat(tentativas.get()).isEqualTo(1)

        advanceTimeBy(5_001)
        assertThat(tentativas.get()).isEqualTo(2)

        job.cancel()
        advanceUntilIdle()
    }

    /**
     * A espera nunca é zero. O único chamador não passa o parâmetro, mas quem passar um `0`
     * (um teste, uma tela nova querendo releitura imediata) transformaria a fonte que falha
     * sempre em rajada — e ninguém perceberia, porque a tela continua igual. Com a guarda, o
     * `0` cai na espera de sempre: uma tentativa agora, outra só depois do intervalo.
     */
    @Test
    fun esperaZeroNaoViraRajada() = runTest {
        val tentativas = AtomicInteger()
        val fonte = flow<AgendaSections> {
            if (tentativas.incrementAndGet() < 4) throw IllegalStateException("o banco não responde")
            emit(vazia)
        }

        val job = launch { agendaUiFrom(fonte, retryDelayMs = 0).collect { } }
        runCurrent()
        // Sem a guarda esta contagem já seria 4: as tentativas todas no mesmo instante.
        assertThat(tentativas.get()).isEqualTo(1)

        advanceTimeBy(AGENDA_RETRY_DELAY_MS + 1)
        assertThat(tentativas.get()).isEqualTo(2)

        job.cancel()
        advanceUntilIdle()
    }

    /**
     * Nem todo `CancellationException` é o cancelamento do coletor. Um `withTimeout` que
     * alguém venha a pôr no `source`, um `ensureActive` de outra camada, sai daqui como
     * cancelamento e mata a coleta calada — o `failed` ficava grudado e a releitura nunca
     * acontecia, que é exatamente o defeito que a releitura existe para consertar. Com o
     * escopo ainda vivo, ele é falha como outra qualquer.
     */
    @Test
    fun cancelamentoQueNaoEhDoColetorNaoMataAColeta() = runBlocking {
        val cheia = AgendaSections(listOf(itemDeHoje("s1:2026-08-20")), emptyList(), emptyList(), emptyList())
        val tentativas = AtomicInteger()
        val fonte = flow<AgendaSections> {
            if (tentativas.incrementAndGet() == 1) throw CancellationException("o timeout de dentro da fonte")
            emit(cheia)
        }

        val estados = withTimeout(5_000) { agendaUiFrom(fonte, retryDelayMs = 1).take(2).toList() }

        assertThat(estados.map { it.failed }).containsExactly(true, false).inOrder()
        assertThat(estados.last().sections).isEqualTo(cheia)
    }

    /**
     * O toque no "Tentar de novo" que falha de novo precisa de resposta: o estado que sai da
     * releitura é igual ao anterior (`AgendaUi` é data class) e o `StateFlow` conflaciona
     * valores iguais — nada mudaria na tela e o toque dela não teria produzido sinal nenhum.
     * O aviso é só do toque: a falha que ninguém pediu não vira recado, senão a home
     * repetiria a frase a cada tentativa automática.
     */
    @Test
    fun oToqueQueFalhaDeNovoAvisaATela() = runTest {
        val tentativas = AtomicInteger()
        val fonte = flow<AgendaSections> {
            tentativas.incrementAndGet()
            throw IllegalStateException("o banco não responde")
        }
        val sinal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val avisos = AtomicInteger()

        val job = launch {
            agendaUiFrom(
                fonte,
                retrySignal = sinal,
                retryDelayMs = 60_000,
                onRetryFailed = { avisos.incrementAndGet() },
            ).collect { }
        }
        runCurrent()
        assertThat(tentativas.get()).isEqualTo(1)
        assertThat(avisos.get()).isEqualTo(0)

        sinal.emit(Unit)
        runCurrent()

        assertThat(tentativas.get()).isEqualTo(2)
        assertThat(avisos.get()).isEqualTo(1)

        job.cancel()
        advanceUntilIdle()
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

package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SpeechSessionTest {
    /** O escopo que no aplicativo é o do ViewModel (não o da tela). Aqui, sem
     *  confinamento, cada passo acontece na hora e o teste não depende de relógio. */
    private fun escopoSemConfinamento() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun recado(titulo: String = "Tomar remédio") = ParsedTaskDraft(
        title = titulo,
        localDate = LocalDate.of(2026, 8, 21),
        localTime = LocalTime.of(8, 0),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
        source = DraftSource.AI,
    )

    @Test
    fun oRecadoEsperaQuemVoltarParaATela() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) { gate.await(); recado() }

        session.understand("tomar remédio amanhã às 8h")
        // A tela pode sair daqui a pouco (rotação, Ajustes): o parse não é dela.
        assertThat(session.state.value.understanding).isTrue()
        assertThat(session.state.value.draft).isNull()

        gate.complete(Unit)

        // Quem volta encontra o rascunho pronto, sem precisar falar de novo.
        assertThat(session.state.value.understanding).isFalse()
        assertThat(session.state.value.draft).isEqualTo(recado())
    }

    @Test
    fun rascunhoEntregueNaoVolta() {
        val session = SpeechSession(escopoSemConfinamento()) { recado() }

        session.understand("tomar remédio amanhã às 8h")
        assertThat(session.state.value.draft).isNotNull()

        session.consumeDraft()
        assertThat(session.state.value.draft).isNull()
    }

    @Test
    fun parseQueFalhaViraRecadoEEspera() {
        val session = SpeechSession(escopoSemConfinamento()) { throw IllegalStateException("sem rede") }

        session.understand("recado esquisito")

        assertThat(session.state.value.error).isEqualTo(UNDERSTAND_FAILED_MESSAGE)
        assertThat(session.state.value.understanding).isFalse()

        session.consumeError()
        assertThat(session.state.value.error).isNull()
    }

    /** O que derrubava o recado na rotação era o CancellationException virar `null` calado. */
    @Test
    fun cancelamentoNaoViraErroNemRascunhoPelaMetade() {
        val scope = escopoSemConfinamento()
        val session = SpeechSession(scope) { throw CancellationException("giro do aparelho") }

        session.understand("tomar remédio amanhã às 8h")
        scope.cancel()

        assertThat(session.state.value.error).isNull()
        assertThat(session.state.value.draft).isNull()
        assertThat(session.state.value.understanding).isFalse()
    }

    @Test
    fun falaVaziaNaoViraParse() {
        var chamadas = 0
        val session = SpeechSession(escopoSemConfinamento()) {
            chamadas++
            recado()
        }

        session.understand("   ")

        assertThat(chamadas).isEqualTo(0)
        assertThat(session.state.value.understanding).isFalse()
    }

    /**
     * O recado que ninguém mais espera não pode aparecer: com ele na sessão, a home o
     * consumiria (`LaunchedEffect(speech.draft)`) e trocaria a tela por baixo dela.
     */
    @Test
    fun oRecadoAbandonadoPorOutraFalaNaoChegaAAparecer() {
        val gate = CompletableDeferred<Unit>()
        val vistos = mutableListOf<SpeechUiState>()
        val scope = escopoSemConfinamento()
        val session = SpeechSession(scope) { texto ->
            if (texto == "tomar remédio") gate.await()
            recado(texto)
        }
        scope.launch { session.state.collect { vistos += it } }

        session.understand("tomar remédio")
        session.understand("tomar água")
        gate.complete(Unit)

        assertThat(vistos.mapNotNull { it.draft?.title }).doesNotContain("tomar remédio")
        assertThat(session.state.value.draft?.title).isEqualTo("tomar água")
    }

    /**
     * Ela escolheu escrever a tarefa em vez de esperar: o parse daquela fala volta depois e
     * não publica nada. Com o rascunho na sessão, a tela de escrita — com o que ela digitou
     * — era trocada pela confirmação do recado falado.
     */
    @Test
    fun oRecadoDescartadoNaoPublicaNadaQuandoOParseVolta() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) { gate.await(); recado() }

        session.understand("comprar pão amanhã às 10:00")
        session.discard()
        assertThat(session.state.value.understanding).isFalse()

        gate.complete(Unit)

        assertThat(session.state.value.draft).isNull()
        assertThat(session.state.value.error).isNull()
        assertThat(session.state.value.understanding).isFalse()
    }

    /** A falha do recado abandonado é tão dela quanto o rascunho: não aparece na escrita. */
    @Test
    fun aFalhaDoRecadoDescartadoNaoApareceNaTelaDeEscrita() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) {
            gate.await()
            throw IllegalStateException("sem rede")
        }

        session.understand("comprar pão amanhã às 10:00")
        session.discard()

        gate.complete(Unit)

        assertThat(session.state.value.error).isNull()
        assertThat(session.state.value.understanding).isFalse()
    }

    /**
     * O parse é uma chamada de rede bloqueante (OkHttp): cancelar o `Job` não a interrompe, e
     * o turno fica com ele até o fim. A espera pelo turno é a espera dela — a tela precisa
     * dizer "Entendendo o recado…" e tirar o "Continuar" da mão dela desde o toque; com o
     * estado limpo por um `discard` (ela tocou em "Escrever tarefa" e voltou) e o anúncio só
     * saindo depois do lock, a tela mostrava o botão livre e sem espera nenhuma — cada toque
     * enfileirava mais um parse.
     */
    @Test
    fun aEsperaPeloTurnoJaSeAnunciaNaTela() {
        val preso = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) { texto ->
            // Como o parse de verdade: a espera não é cancelável.
            if (texto == "comprar pão") withContext(NonCancellable) { preso.await() }
            recado(texto)
        }

        session.understand("comprar pão")
        session.discard()
        session.understand("tomar água")

        // O turno ainda é do parse abandonado, e é isso que a tela tem que mostrar.
        assertThat(session.state.value.understanding).isTrue()
        assertThat(session.state.value.draft).isNull()

        preso.complete(Unit)

        // Terminada a espera, quem manda é a fala nova — e a tela para de esperar.
        assertThat(session.state.value.understanding).isFalse()
        assertThat(session.state.value.draft?.title).isEqualTo("tomar água")
    }

    /** O que ela escreveu e mandou de novo não fica atrás de um parse abandonado. */
    @Test
    fun oRecadoAbandonadoNaoSeguraOTurnoDoProximo() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) { texto ->
            if (texto == "comprar pão") gate.await()
            recado(texto)
        }

        session.understand("comprar pão")
        session.discard()
        session.understand("tomar água")

        assertThat(session.state.value.draft?.title).isEqualTo("tomar água")
    }

    @Test
    fun umRecadoPorVezNaoAtropelaOOutro() {
        val gate = CompletableDeferred<Unit>()
        val pedidos = mutableListOf<String>()
        val session = SpeechSession(escopoSemConfinamento()) { texto ->
            pedidos += texto
            if (pedidos.size == 1) gate.await()
            recado(texto)
        }

        session.understand("tomar remédio")
        session.understand("tomar água")
        gate.complete(Unit)

        assertThat(pedidos).containsExactly("tomar remédio", "tomar água").inOrder()
        assertThat(session.state.value.draft?.title).isEqualTo("tomar água")
        assertThat(session.state.value.understanding).isFalse()
    }
}

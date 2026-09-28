package com.theopadilha.falaagenda.ui.home

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

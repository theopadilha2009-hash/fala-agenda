package com.theopadilha.falaagenda.ui.capture

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.ui.home.SpeechSession
import com.theopadilha.falaagenda.ui.home.SpeechUiState
import com.theopadilha.falaagenda.ui.home.UNDERSTAND_FAILED_MESSAGE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * A tela de escrita não decide nada sobre o parse: ela pede e reage. Girar o aparelho no
 * meio dos ~20 s do parse com IA recriava a tela e a coroutine morria junto — o texto
 * ficava no campo e nada acontecia, nem rascunho, nem erro, nem mudança de tela. O
 * desfecho agora espera na sessão do ViewModel e quem voltar o encontra: é essa espera
 * que estes testes prendem.
 */
class WriteStepTest {
    /** O escopo que no aplicativo é o do ViewModel (não o da tela). Aqui, sem
     *  confinamento, cada passo acontece na hora e o teste não depende de relógio. */
    private fun escopoSemConfinamento() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun recado(titulo: String = "Tomar remédio") = ParsedTaskDraft(
        title = titulo,
        localDate = LocalDate.of(2026, 9, 28),
        localTime = LocalTime.of(9, 0),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = titulo,
        source = DraftSource.AI,
    )

    @Test
    fun semNadaEntendidoATelaFicaOndeEsta() {
        assertThat(writeStepFor(SpeechUiState())).isEqualTo(WriteStep.Waiting)
    }

    @Test
    fun enquantoEntendeNaoSaiDaTelaComORecadoPelaMetade() {
        assertThat(writeStepFor(SpeechUiState(understanding = true))).isEqualTo(WriteStep.Waiting)
    }

    @Test
    fun rascunhoProntoMandaParaAConfirmacao() {
        assertThat(writeStepFor(SpeechUiState(draft = recado())))
            .isEqualTo(WriteStep.Ready(recado()))
    }

    /** A frase que ela lê aqui não pode ser a da home, que manda escrever a tarefa. */
    @Test
    fun parseQueFalhaExplicaNaPropriaTela() {
        assertThat(writeStepFor(SpeechUiState(error = UNDERSTAND_FAILED_MESSAGE)))
            .isEqualTo(WriteStep.Failed("Não consegui entender o recado. Tente de novo."))
    }

    @Test
    fun oRecadoEsperaATelaSerRecriada() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) { gate.await(); recado() }

        // Ela apertou Continuar e o aparelho girou antes de o parse terminar.
        session.understand("tomar remédio amanhã às 9h")
        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Waiting)

        gate.complete(Unit)

        // A tela recriada encontra o rascunho pronto, em vez de um campo com texto e mais nada.
        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Ready(recado()))

        // Entregue uma vez, ele não manda de novo para a confirmação na composição seguinte.
        session.consumeDraft()
        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Waiting)
    }

    /**
     * Ela tocou "Escrever tarefa" com o parse em voo e começou a digitar: a espera acabou
     * ali. O parse daquela fala volta depois — e não pode levar a tela (com o texto dela
     * dentro) para a confirmação, nem deixar o "Continuar" preso a uma espera que não é mais
     * dela.
     */
    @Test
    fun aFalaAbandonadaNaoTrocaATelaDeEscrita() {
        val gate = CompletableDeferred<Unit>()
        val session = SpeechSession(escopoSemConfinamento()) { gate.await(); recado() }

        session.understand("comprar pão amanhã às 10:00")
        session.discard()
        // O botão "Continuar" sai do estado da sessão: sem espera, ele está na mão dela.
        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Waiting)

        gate.complete(Unit)

        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Waiting)
    }

    @Test
    fun oErroTambemEsperaATelaSerRecriada() {
        val session = SpeechSession(escopoSemConfinamento()) { throw IllegalStateException("sem rede") }

        session.understand("recado esquisito")

        // Sem isto a falha sumia junto com a tela, e ela não sabia se o recado tinha ido.
        assertThat(writeStepFor(session.state.value))
            .isEqualTo(WriteStep.Failed("Não consegui entender o recado. Tente de novo."))

        session.consumeError()
        assertThat(writeStepFor(session.state.value)).isEqualTo(WriteStep.Waiting)
    }
}

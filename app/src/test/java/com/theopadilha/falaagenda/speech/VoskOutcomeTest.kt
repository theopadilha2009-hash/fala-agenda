package com.theopadilha.falaagenda.speech

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O que o Vosk devolve é JSON, e a chave muda entre o parcial e o resultado fechado. */
class VoskOutcomeTest {
    @Test
    fun parcialSaiDaChavePartial() {
        assertThat(VoskOutcome.partial("""{"partial" : "tomar remédio"}"""))
            .isEqualTo("tomar remédio")
    }

    @Test
    fun resultadoSaiDaChaveText() {
        assertThat(VoskOutcome.text("""{"text" : "tomar remédio amanhã às oito"}"""))
            .isEqualTo("tomar remédio amanhã às oito")
    }

    @Test
    fun silencioReconhecidoNaoViraTexto() {
        assertThat(VoskOutcome.text("""{"text" : ""}""")).isEmpty()
        assertThat(VoskOutcome.partial("""{"partial" : ""}""")).isEmpty()
    }

    @Test
    fun semResultadoEhVazioENaoExplode() {
        assertThat(VoskOutcome.text("{}")).isEmpty()
        assertThat(VoskOutcome.partial("{}")).isEmpty()
    }

    @Test
    fun jsonQuebradoNaoDerrubaAEscuta() {
        assertThat(VoskOutcome.text("não é json")).isEmpty()
        assertThat(VoskOutcome.partial("")).isEmpty()
    }

    @Test
    fun espacoSobrandoSaiDoTexto() {
        assertThat(VoskOutcome.text("""{"text" : "  tomar água  "}""")).isEqualTo("tomar água")
    }
}

package com.theopadilha.falaagenda.ui.month

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.YearMonth

class MonthSummaryTextTest {
    private val august = YearMonth.of(2026, 8)

    /** Mês passado já aconteceu: "ainda" lia como se o mês não tivesse chegado. */
    @Test
    fun mesPassadoNaoFalaEmAinda() {
        val text = emptyFrequentMessage(YearMonth.of(2026, 7), august)
        assertThat(text).doesNotContain("ainda")
        assertThat(text).isEqualTo("Você não marcou nada como feito neste mês.")
    }

    @Test
    fun mesCorrenteMantemOTextoDeAinda() {
        assertThat(emptyFrequentMessage(august, august))
            .isEqualTo("Você ainda não marcou nada como feito neste mês. Quando marcar, aparece aqui.")
    }

    /** Mês futuro também está por vir: o texto com "ainda" continua valendo. */
    @Test
    fun mesFuturoUsaOTextoDoMesCorrente() {
        assertThat(emptyFrequentMessage(YearMonth.of(2026, 9), august))
            .isEqualTo("Você ainda não marcou nada como feito neste mês. Quando marcar, aparece aqui.")
    }

    /**
     * O vocabulário é o dela. O botão da agenda diz "Concluir", mas o que ela fala é "fiz";
     * "concluído" e "concluir tarefas" são a língua do código, não a da conversa — e era o
     * que a tela respondia quando não havia nada na lista.
     */
    @Test
    fun oRecadoVazioFalaComoElaENaoComoOCodigo() {
        val passado = emptyFrequentMessage(YearMonth.of(2026, 7), august)
        val corrente = emptyFrequentMessage(august, august)

        listOf(passado, corrente).forEach { text ->
            assertThat(text).contains("marcou")
            assertThat(text).doesNotContain("concluí")
            assertThat(text).doesNotContain("concluir")
        }
    }
}

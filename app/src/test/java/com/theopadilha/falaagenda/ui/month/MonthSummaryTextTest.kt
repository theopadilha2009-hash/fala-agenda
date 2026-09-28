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
        assertThat(text).isEqualTo("Nada foi concluído neste mês.")
    }

    @Test
    fun mesCorrenteMantemOTextoDeAinda() {
        assertThat(emptyFrequentMessage(august, august))
            .isEqualTo("Nada neste mês ainda. Quando concluir tarefas, elas aparecem aqui.")
    }

    /** Mês futuro também está por vir: o texto com "ainda" continua valendo. */
    @Test
    fun mesFuturoUsaOTextoDoMesCorrente() {
        assertThat(emptyFrequentMessage(YearMonth.of(2026, 9), august))
            .isEqualTo("Nada neste mês ainda. Quando concluir tarefas, elas aparecem aqui.")
    }
}

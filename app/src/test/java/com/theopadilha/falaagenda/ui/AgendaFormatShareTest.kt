package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * A pendente que atravessou a meia-noite entra na seção "Hoje" (`sectionsOf`), então o item
 * de ontem precisa se distinguir do de hoje tanto na linha que ela vê quanto no texto que
 * vai para a família.
 */
class AgendaFormatShareTest {
    private val hoje = LocalDate.of(2026, 8, 20)
    private val ontem = hoje.minusDays(1)

    @Test
    fun tarefaDeHojeSaiSemMarca() {
        val text = AgendaFormat.todayShare(
            listOf(
                AgendaFormat.DayShareLine(
                    title = "Remédio",
                    time = LocalTime.of(8, 0),
                    dayMark = AgendaFormat.shareDayMark(hoje, hoje),
                ),
            ),
        )
        assertThat(text).contains("• Remédio às 08:00")
        assertThat(text).doesNotContain("ontem")
    }

    @Test
    fun tarefaDeOntemSaiMarcadaNoCompartilhado() {
        val text = AgendaFormat.todayShare(
            listOf(
                AgendaFormat.DayShareLine(
                    title = "Remédio",
                    time = LocalTime.of(8, 0),
                    observation = "com água",
                    dayMark = AgendaFormat.shareDayMark(ontem, hoje),
                ),
            ),
        )
        assertThat(text).contains("• Remédio, ontem às 08:00 — com água")
    }

    @Test
    fun tarefaDeDiaMaisAntigoSaiComAData() {
        val text = AgendaFormat.todayShare(
            listOf(
                AgendaFormat.DayShareLine(
                    title = "Cabelo",
                    time = LocalTime.of(15, 0),
                    dayMark = AgendaFormat.shareDayMark(LocalDate.of(2026, 8, 15), hoje),
                ),
            ),
        )
        assertThat(text).contains("• Cabelo, 15/08 às 15:00")
    }

    @Test
    fun marcaDoCompartilhadoSoExisteForaDeHoje() {
        assertThat(AgendaFormat.shareDayMark(hoje, hoje)).isNull()
        assertThat(AgendaFormat.shareDayMark(ontem, hoje)).isEqualTo("ontem")
        assertThat(AgendaFormat.shareDayMark(LocalDate.of(2026, 8, 15), hoje)).isEqualTo("15/08")
    }

    @Test
    fun linhaAtrasadaSoExisteDepoisDoDia() {
        assertThat(AgendaFormat.lateMark(ontem, hoje)).isEqualTo("atrasada")
        assertThat(AgendaFormat.lateMark(LocalDate.of(2026, 8, 15), hoje)).isEqualTo("atrasada")
        // De hoje em diante a linha mostra o de sempre ("daqui 20 min", recorrência).
        assertThat(AgendaFormat.lateMark(hoje, hoje)).isNull()
        assertThat(AgendaFormat.lateMark(hoje.plusDays(1), hoje)).isNull()
    }
}

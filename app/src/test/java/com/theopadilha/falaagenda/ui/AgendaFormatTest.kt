package com.theopadilha.falaagenda.ui

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AgendaFormatTest {
    private val today = LocalDate.of(2026, 8, 20)
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun rotulosRelativos() {
        assertThat(AgendaFormat.dateLabel(today, today)).isEqualTo("Hoje")
        assertThat(AgendaFormat.dateLabel(today.plusDays(1), today)).isEqualTo("Amanhã")
        assertThat(AgendaFormat.dateLabel(today.minusDays(1), today)).isEqualTo("Ontem")
        assertThat(AgendaFormat.dateLabel(LocalDate.of(2026, 8, 25), today)).isEqualTo("25/08")
    }

    @Test
    fun dataPorExtensoEmPortugues() {
        val text = AgendaFormat.longDate(today)
        assertThat(text.lowercase()).contains("agosto")
        assertThat(text).contains("2026")
        assertThat(text).contains("20")
    }

    @Test
    fun horario24h() {
        assertThat(AgendaFormat.time(LocalTime.of(9, 5))).isEqualTo("09:05")
        assertThat(AgendaFormat.time(LocalTime.of(21, 0))).isEqualTo("21:00")
    }

    @Test
    fun saudacaoPorFaixa() {
        assertThat(AgendaFormat.greeting(LocalTime.of(8, 0))).isEqualTo("Bom dia")
        assertThat(AgendaFormat.greeting(LocalTime.of(15, 0))).isEqualTo("Boa tarde")
        assertThat(AgendaFormat.greeting(LocalTime.of(21, 0))).isEqualTo("Boa noite")
        assertThat(AgendaFormat.greeting(LocalTime.of(2, 0))).isEqualTo("Boa noite")
    }

    @Test
    fun anunciaQuandoAvisa() {
        assertThat(AgendaFormat.announce(today, LocalTime.of(8, 0), today))
            .isEqualTo("Vai avisar hoje às 08:00.")
        assertThat(AgendaFormat.announce(today.plusDays(1), LocalTime.of(14, 30), today))
            .isEqualTo("Vai avisar amanhã às 14:30.")
    }

    @Test
    fun headlineComProximoEAtrasado() {
        val text = AgendaFormat.headline(
            nowTime = LocalTime.of(8, 0),
            today = today,
            nextTitle = "Tomar remédio",
            nextDate = today,
            nextTime = LocalTime.of(9, 0),
            missedCount = 2,
        )
        assertThat(text).isEqualTo("Bom dia. Próximo: Tomar remédio, hoje às 09:00. 2 recados ficaram para trás.")
    }

    @Test
    fun headlineVazio() {
        val text = AgendaFormat.headline(
            nowTime = LocalTime.of(19, 0),
            today = today,
            nextTitle = null,
            nextDate = null,
            nextTime = null,
            missedCount = 0,
        )
        assertThat(text).isEqualTo("Boa noite. Nada marcado agora.")
    }

    @Test
    fun recapCompleto() {
        val text = AgendaFormat.recap(today, LocalTime.of(8, 0), RecurrenceRule(RecurrenceKind.DAILY))
        assertThat(text).contains("08:00")
        assertThat(text).contains("Todos os dias")
        assertThat(text).startsWith("Vai avisar")
    }

    @Test
    fun fromNowMinutosEHoras() {
        val now = LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant()
        assertThat(AgendaFormat.fromNow(now.plusSeconds(15 * 60), now)).isEqualTo("daqui 15 min")
        assertThat(AgendaFormat.fromNow(now.plusSeconds(2 * 3600), now)).isEqualTo("daqui 2 h")
        assertThat(AgendaFormat.fromNow(now.minusSeconds(20 * 60), now)).isEqualTo("há 20 min")
        assertThat(AgendaFormat.fromNow(now.plusSeconds(3 * 24 * 3600), now)).isNull()
    }
}

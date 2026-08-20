package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class LocalTaskParserTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    @Test
    fun hojeEHora() {
        val draft = parser.parse("Me lembrar de tomar remédio hoje às 21h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(21, 0))
        assertThat(draft.title.lowercase()).contains("remédio".lowercase().take(6))
        assertThat(draft.missingFields).isEmpty()
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun amanhaMeioDia() {
        val draft = parser.parse("almoço amanhã meio-dia")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.NOON)
    }

    @Test
    fun depoisDeAmanha() {
        val draft = parser.parse("dentista depois de amanhã às 9h30")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 30))
    }

    @Test
    fun dataNumericaEHorario() {
        val draft = parser.parse("prova 25/12 às 09:30")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 12, 25))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 30))
    }

    @Test
    fun dataPorExtenso() {
        val draft = parser.parse("consulta 3 de março às 8h")
        assertThat(draft.localDate!!.monthValue).isEqualTo(3)
        assertThat(draft.localDate!!.dayOfMonth).isEqualTo(3)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
    }

    @Test
    fun naoInventaDataAusente() {
        val draft = parser.parse("tomar remédio")
        assertThat(draft.missingFields).contains(MissingDraftField.DATE)
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.localDate).isNull()
        assertThat(draft.localTime).isNull()
        assertThat(draft.notes.joinToString()).contains("Não inventamos")
    }

    @Test
    fun naoInventaHoraAusente() {
        val draft = parser.parse("reunião amanhã")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isNull()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
    }

    @Test
    fun dataPassadaGeraNota() {
        val draft = parser.parse("reunião 01/01/2026 às 9h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 1, 1))
        assertThat(draft.notes.joinToString().lowercase()).contains("passaram")
    }

    @Test
    fun todoDia() {
        val draft = parser.parse("todo dia tomar vitamina às 8h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.DAILY)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.localDate).isNotNull()
    }

    @Test
    fun diasUteis() {
        val draft = parser.parse("dias úteis reunião às 9h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKDAYS)
    }

    @Test
    fun todaSegundaEQuarta() {
        val draft = parser.parse("toda segunda e quarta natação às 18h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(draft.recurrence.weekDays).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun todoDiaQuinzeDoMes() {
        val draft = parser.parse("todo dia 15 do mês pagar contas às 10h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertThat(draft.recurrence.dayOfMonth).isEqualTo(15)
    }

    @Test
    fun todoVinteNoveDeFevereiro() {
        val draft = parser.parse("todo 29 de fevereiro revisar documentos às 11h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.YEARLY)
        assertThat(draft.recurrence.monthOfYear).isEqualTo(2)
        assertThat(draft.recurrence.dayOfMonth).isEqualTo(29)
    }

    @Test
    fun weekdayAvulsoUsaProximaOcorrencia() {
        val draft = parser.parse("sexta buscar as crianças às 17h")
        assertThat(draft.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.FRIDAY)
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
    }
}

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

    @Test
    fun meLembraAmanhaAsNove() {
        val draft = parser.parse("me lembra amanhã às nove de tomar o remédio")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.title.lowercase()).contains("remédio")
        assertThat(draft.missingFields).isEmpty()
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun todoDiaAsOito() {
        val draft = parser.parse("todo dia às oito")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.DAILY)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.localDate).isNotNull()
    }

    @Test
    fun todaSegundaEQuartaAsDez() {
        val draft = parser.parse("toda segunda e quarta às dez")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(draft.recurrence.weekDays).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun nosDiasUteisAsSete() {
        val draft = parser.parse("nos dias úteis às sete")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKDAYS)
        assertThat(draft.localTime).isEqualTo(LocalTime.of(7, 0))
    }

    @Test
    fun dia31DeCadaMes() {
        val draft = parser.parse("dia 31 de cada mês")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertThat(draft.recurrence.dayOfMonth).isEqualTo(31)
        assertThat(draft.localTime).isNull()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
    }

    @Test
    fun todoAnoDia10DeMaio() {
        val draft = parser.parse("todo ano dia 10 de maio")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.YEARLY)
        assertThat(draft.recurrence.dayOfMonth).isEqualTo(10)
        assertThat(draft.recurrence.monthOfYear).isEqualTo(5)
    }

    @Test
    fun daquiAMeiaHora() {
        val draft = parser.parse("daqui a meia hora")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 30))
    }

    @Test
    fun daquiDezMinutosSemA() {
        val draft = parser.parse("tomar remédio daqui 10 minutos")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 10))
        assertThat(draft.title.lowercase()).contains("tomar")
    }

    @Test
    fun daquiMeiaHoraSemA() {
        val draft = parser.parse("daqui meia hora")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 30))
    }

    @Test
    fun hojeANoiteNaoInventaHora() {
        val draft = parser.parse("hoje à noite")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.notes.joinToString()).contains("Não inventamos")
    }

    @Test
    fun sextaFeiraDepoisDoAlmocoNaoInventaHora() {
        val draft = parser.parse("sexta-feira depois do almoço")
        assertThat(draft.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.FRIDAY)
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
    }

    @Test
    fun hojeANoiteAsNoveVira21h() {
        val draft = parser.parse("hoje à noite às nove")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(21, 0))
    }
}

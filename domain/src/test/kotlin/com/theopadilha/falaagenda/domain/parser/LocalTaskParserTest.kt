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

    private fun parserEm(dateTime: LocalDateTime) =
        LocalTaskParser(FixedAppClock(dateTime.atZone(zone).toInstant(), zone))

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

    @Test
    fun amanhaAsNoveEMeia() {
        val draft = parser.parse("me lembrar de tomar remédio amanhã às 9 e meia")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 30))
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun asNoveEVinte() {
        val draft = parser.parse("me lembrar de tomar remédio amanhã às nove e vinte")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 20))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun oitoHorasEMeia() {
        val draft = parser.parse("tomar remédio às 8 horas e meia")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 30))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun meioDiaEMeia() {
        val draft = parser.parse("consulta meio-dia e meia")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(12, 30))
        assertThat(draft.title).isEqualTo("Consulta")
    }

    @Test
    fun amanhaAsTresDeTarde() {
        val draft = parser.parse("reunião amanhã às 3 de tarde")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun asOitoDeNoite() {
        val draft = parser.parse("tomar remédio às 8 de noite")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(20, 0))
    }

    @Test
    fun asTresSemPeriodoNaoInventa() {
        val draft = parser.parse("reunião amanhã às 3")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun todoDiaUtilViraDiasUteis() {
        val draft = parser.parse("todo dia útil tomar remédio às 8h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKDAYS)
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun daquiCincoMinutos() {
        val draft = parser.parse("tomar remédio daqui cinco minutos")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 5))
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.missingFields).isEmpty()
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun daquiCincoMinAbreviado() {
        val draft = parser.parse("tomar remédio daqui 5 min")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 5))
    }

    @Test
    fun daquiCincoMinComPonto() {
        val draft = parser.parse("tomar remédio daqui 5 min.")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 5))
    }

    @Test
    fun todaQuartaComHoraPassadaVaiParaProximaSemana() {
        val quarta = parserEm(LocalDateTime.of(2026, 8, 19, 10, 0))
        val draft = quarta.parse("toda quarta natação às 9h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 26))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.notes.joinToString()).doesNotContain("passaram")
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun todoDiaComHoraPassadaVaiParaAmanha() {
        val draft = parser.parse("todo dia tomar vitamina às 8h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.DAILY)
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
    }

    @Test
    fun unicaComHoraPassadaMantemNota() {
        val draft = parser.parse("reunião hoje às 9h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.notes.joinToString().lowercase()).contains("passaram")
    }

    // hoje é quinta 20/08/2026: "que vem" no dia da semana é a ocorrência da próxima semana

    @Test
    fun quintaQueVemVaiParaAProximaSemana() {
        val draft = parser.parse("quinta que vem dentista às 17h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(17, 0))
        assertThat(draft.title).isEqualTo("Dentista")
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun sextaQueVemVaiParaAProximaSemana() {
        val draft = parser.parse("sexta que vem buscar as crianças às 17h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 28))
        assertThat(draft.title).contains("Buscar")
        assertThat(draft.title).doesNotContain("Vem")
    }

    @Test
    fun sabadoQueVemVaiParaAProximaSemana() {
        val draft = parser.parse("sábado que vem missa às 9h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 29))
        assertThat(draft.title).isEqualTo("Missa")
    }

    @Test
    fun proximaSextaVaiParaAProximaSemana() {
        val draft = parser.parse("próxima sexta dentista às 17h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 28))
        assertThat(draft.title).isEqualTo("Dentista")
    }

    @Test
    fun todaQuintaQueVemComecaNaProximaSemana() {
        val draft = parser.parse("toda quinta que vem natação às 18h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(draft.recurrence.weekDays).containsExactly(DayOfWeek.THURSDAY)
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(draft.title).isEqualTo("Natação")
    }

    @Test
    fun remedioDeOitoEmOitoHorasNaoViraHorarioFixo() {
        val frases = listOf(
            "remédio de 8 em 8 horas",
            "remédio de 12 em 12 horas",
            "remédio a cada 8 horas",
            "remédio cada 2 horas",
            "remédio a cada duas horas",
            "remédio de 30 em 30 minutos",
        )
        frases.forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.missingFields).contains(MissingDraftField.TIME)
            assertThat(draft.title).isEqualTo("Remédio")
            assertThat(draft.notes.joinToString()).contains("intervalo")
        }
    }

    @Test
    fun minutosCompostosPorExtensoNaoPerdemAUnidade() {
        val quarentaECinco = parser.parse("às nove e quarenta e cinco tomar remédio")
        assertThat(quarentaECinco.localTime).isEqualTo(LocalTime.of(9, 45))
        assertThat(quarentaECinco.title).isEqualTo("Tomar remédio")

        val vinteECinco = parser.parse("tomar remédio às dez e vinte e cinco")
        assertThat(vinteECinco.localTime).isEqualTo(LocalTime.of(10, 25))
        assertThat(vinteECinco.title).isEqualTo("Tomar remédio")

        val trintaECinco = parser.parse("tomar remédio às 7 e trinta e cinco")
        assertThat(trintaECinco.localTime).isEqualTo(LocalTime.of(7, 35))
        assertThat(trintaECinco.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun emDuasHorasContinuaSendoRelativo() {
        val draft = parser.parse("tomar remédio em duas horas")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(12, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun daquiDuasHorasEMeia() {
        val draft = parser.parse("tomar remédio daqui a duas horas e meia")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(12, 30))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun daquiUmaHoraEMeia() {
        val draft = parser.parse("tomar remédio daqui a uma hora e meia")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(11, 30))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun asDozeDaNoiteViraMeiaNoite() {
        val draft = parser.parse("tomar remédio às 12 da noite")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(0, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }
}

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
    fun asTresSemPeriodoViraAmbigua() {
        // "amanhã às três" pode ser 3 da manhã ou 3 da tarde. Antes cravava 03:00 com cara de
        // certeza; agora mantém o palpite no rascunho mas marca ambíguo, para escalar/confirmar.
        val draft = parser.parse("reunião amanhã às 3")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(draft.ambiguous).isTrue()
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

    // ---- Frases que ela realmente fala: duas tarefas, horas por extenso, datas relativas, dia por extenso ----

    @Test
    fun duasTarefasNaMesmaFraseViramAmbiguas() {
        // "marcar médico terça e tomar remédio às oito": dois verbos, um dia e uma hora.
        // Antes o parser colava tudo num título só ("Marcar médico tomar remédio"), terça 08:00,
        // com cara de certeza. Agora marca ambíguo para escalar/confirmar em vez de adivinhar.
        val draft = parser.parse("marcar médico terça e tomar remédio às oito")
        assertThat(draft.ambiguous).isTrue()
        // Uma hora completa no rascunho de duas tarefas é uma hora confirmável a menos: sem hora,
        // a caixa rápida não deixa passar.
        assertThat(draft.localTime).isNull()
    }

    @Test
    fun duasTarefasComRecorrenciaNaSegundaViramAmbiguas() {
        val draft = parser.parse("marcar médico terça e tomar remédio todo dia às oito")
        assertThat(draft.ambiguous).isTrue()
    }

    @Test
    fun umaTarefaSoNaoViraAmbigua() {
        val draft = parser.parse("buscar as crianças amanhã às 15h")
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
    }

    @Test
    fun horasPorExtensoDaTardeENoite() {
        val quinze = parser.parse("fisioterapia às quinze horas")
        assertThat(quinze.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(quinze.ambiguous).isFalse()

        val dezesseis = parser.parse("tomar remédio às dezesseis horas")
        assertThat(dezesseis.localTime).isEqualTo(LocalTime.of(16, 0))

        val vinteETres = parser.parse("dormir às vinte e três horas")
        assertThat(vinteETres.localTime).isEqualTo(LocalTime.of(23, 0))
    }

    @Test
    fun relogioPorExtensoComMinutosSemAs() {
        // O Vosk pt-BR costuma devolver a hora por extenso ("oito e meia"); sem o "às" o parser não achava nada.
        val draft = parser.parse("tomar remédio oito e meia")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 30))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun semanaQueVemSemDiaDaSemana() {
        val draft = parser.parse("reunião semana que vem às 15h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(draft.title).isEqualTo("Reunião")
    }

    @Test
    fun noDiaVinteECincoDoMes() {
        val draft = parser.parse("pagar conta no dia 25 às 15h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 25))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(draft.title).isEqualTo("Pagar conta")
    }

    @Test
    fun noComecoDoMesViraPrimeiroDoProximo() {
        val draft = parser.parse("pagar aluguel no começo do mês")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(draft.title).isEqualTo("Pagar aluguel")
    }

    @Test
    fun daquiAPoucoNaoInventaHoraExata() {
        val draft = parser.parse("tomar remédio daqui a pouco")
        assertThat(draft.ambiguous).isTrue()
    }

    @Test
    fun noitinhaViraNoite() {
        val draft = parser.parse("tomar remédio à noitinha às oito")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun diaDoMesPorExtenso() {
        val draft = parser.parse("pagar conta dois de maio às 15h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 5, 2))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
    }

    @Test
    fun asDozeEMeiaDaNoiteViraMeiaNoiteEMeia() {
        val draft = parser.parse("dormir às 12 e meia da noite")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(0, 30))
        assertThat(draft.title).isEqualTo("Dormir")
    }

    @Test
    fun todoDiaComNumeroSemMesContinuaDiario() {
        // "todo dia 5" é todo dia; mensal é "todo dia 5 do mês". Antes virava mensal em silêncio.
        val draft = parser.parse("todo dia 5 caminhar")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.DAILY)
        assertThat(draft.recurrence.dayOfMonth).isNull()
    }

    // ---- Correções do review: o parser não pode cravar hora/data errada com cara de certeza ----

    @Test
    fun relogioPorExtensoComMinutoComposto() {
        // B1: "oito e vinte e cinco" sem o "às" é 08:25. A versão anterior lia só a dezena (08:20)
        // e ainda comia o "cinco" do título.
        val vinteCinco = parser.parse("tomar remédio oito e vinte e cinco")
        assertThat(vinteCinco.localTime).isEqualTo(LocalTime.of(8, 25))
        assertThat(vinteCinco.title).isEqualTo("Tomar remédio")

        val quarentaCinco = parser.parse("tomar remédio oito e quarenta e cinco")
        assertThat(quarentaCinco.localTime).isEqualTo(LocalTime.of(8, 45))
        assertThat(quarentaCinco.title).isEqualTo("Tomar remédio")

        // Contraste que já funcionava: com o "às".
        val comAs = parser.parse("tomar remédio às oito e vinte e cinco")
        assertThat(comAs.localTime).isEqualTo(LocalTime.of(8, 25))
    }

    @Test
    fun diaDoMesPorExtensoNaoViraHora() {
        // B2: "às vinte e cinco de maio" é a data (25 de maio), não 20:00 com o dia 5.
        val draft = parser.parse("pagar conta às vinte e cinco de maio")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 5, 25))
        assertThat(draft.localTime).isNull()

        // Sem o "às" já funcionava — não pode regredir.
        val semAs = parser.parse("pagar conta vinte e cinco de maio")
        assertThat(semAs.localDate).isEqualTo(LocalDate.of(2027, 5, 25))
    }

    @Test
    fun horaVinteECompostoNaoDeixaRestoNoTitulo() {
        // B3: "às vinte e cinco" não é 20:00 com o "cinco" sobrando no título; 25 não é hora válida,
        // então fica ambíguo em vez de cravar.
        val vinteCinco = parser.parse("tomar remédio às vinte e cinco")
        assertThat(vinteCinco.ambiguous).isTrue()
        assertThat(vinteCinco.title).isEqualTo("Tomar remédio")

        val vinteQuatro = parser.parse("tomar remédio às vinte e quatro")
        assertThat(vinteQuatro.ambiguous).isTrue()
        assertThat(vinteQuatro.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun periodoDaNoiteComHoraMadrugadaNaoViraTarde() {
        // B4: "às 3 e meia da noite" é 03:30 (madrugada), não 15:30.
        val noite = parser.parse("tomar remédio às 3 e meia da noite")
        assertThat(noite.localTime).isEqualTo(LocalTime.of(3, 30))

        // "da tarde" continua somando 12.
        val tarde = parser.parse("tomar remédio às 3 e meia da tarde")
        assertThat(tarde.localTime).isEqualTo(LocalTime.of(15, 30))

        // "da noite" com hora de fim de tarde/noite continua somando 12.
        val oito = parser.parse("tomar remédio às 8 e meia da noite")
        assertThat(oito.localTime).isEqualTo(LocalTime.of(20, 30))
    }

    @Test
    fun duasTarefasComSegundaOracaoSemVerboViramAmbiguas() {
        // B5: a segunda oração sem verbo ("e remédio às oito") é a forma natural na fala dela —
        // o dia e a hora caem em orações diferentes, o sinal de duas tarefas.
        val draft = parser.parse("marcar médico terça e remédio às oito")
        assertThat(draft.ambiguous).isTrue()

        // Uma série ("toda terça e quinta às 18h") tem dia e hora na mesma oração: não é ambígua.
        val serie = parser.parse("toda terça e quinta natação às 18h")
        assertThat(serie.ambiguous).isFalse()
    }

    @Test
    fun doisDiasDoMesNaMesmaFraseViraAmbiguo() {
        // B6: "no dia 25 e no dia 30" são duas datas, não a primeira em silêncio.
        val draft = parser.parse("pagar conta no dia 25 e no dia 30")
        assertThat(draft.ambiguous).isTrue()
    }

    // ---- Auditoria de 06/10/2026: datas nomeadas, bordas do mês, "do mês que vem" e relativos em dias ----

    @Test
    fun sextaFeiraSantaNaoViraASextaDestaSemana() {
        // O pior do lote: "sexta-feira santa" casava o dia da semana e devolvia a sexta DESTA
        // semana (21/08/2026) às 15h, completa e não-ambígua — a caixa rápida confirmava e a missa
        // era agendada no dia errado, sem nunca consultar a IA. A Sexta-feira Santa de 2026 já
        // passou (03/04); a próxima é 26/03/2027.
        val draft = parser.parse("sexta-feira santa missa às 15h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 3, 26))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(draft.title).isEqualTo("Missa")
        assertThat(draft.missingFields).isEmpty()
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun cinzasECorpusChristiDerivamDaPascoa() {
        // Cinzas e Corpus Christi de 2026 já passaram (18/02 e 04/06): as próximas são de 2027.
        val cinzas = parser.parse("quarta-feira de cinzas missa às 19h")
        assertThat(cinzas.localDate).isEqualTo(LocalDate.of(2027, 2, 10))
        assertThat(cinzas.title).isEqualTo("Missa")

        val corpus = parser.parse("corpus christi missa às 9h")
        assertThat(corpus.localDate).isEqualTo(LocalDate.of(2027, 5, 27))
        assertThat(corpus.title).isEqualTo("Missa")
    }

    @Test
    fun movelDoAnoQueVemQuandoODeHojeJaPassou() {
        // Em janeiro, a Sexta-feira Santa de 2027 ainda não passou: fica no próprio ano.
        val janeiro = parserEm(LocalDateTime.of(2027, 1, 10, 10, 0))
        assertThat(janeiro.parse("sexta-feira santa missa às 15h").localDate)
            .isEqualTo(LocalDate.of(2027, 3, 26))
    }

    @Test
    fun pascoaSemAnoNaoCravaData() {
        // A Páscoa cai entre 22/03 e 25/04: sem o ano, cravar seria chute. Fica sem data e o
        // `HybridParser` escala — o desfecho honesto.
        val draft = parser.parse("na Páscoa missa")
        assertThat(draft.localDate).isNull()
        assertThat(draft.missingFields).contains(MissingDraftField.DATE)
    }

    @Test
    fun datasFixasDoCalendario() {
        val natal = parser.parse("no Natal almoço às 13h")
        assertThat(natal.localDate).isEqualTo(LocalDate.of(2026, 12, 25))
        assertThat(natal.localTime).isEqualTo(LocalTime.of(13, 0))

        val finados = parser.parse("dia de finados missa às 10h")
        assertThat(finados.localDate).isEqualTo(LocalDate.of(2026, 11, 2))
        assertThat(finados.title).isEqualTo("Missa")

        // 12/06/2026 já passou (hoje é 20/08/2026): o próximo é 2027.
        val namorados = parser.parse("no dia dos namorados jantar às 20h")
        assertThat(namorados.localDate).isEqualTo(LocalDate.of(2027, 6, 12))
        assertThat(namorados.title).isEqualTo("Jantar")
    }

    @Test
    fun diaDasMaesEhOSegundoDomingoDeMaio() {
        // 2º domingo de maio de 2027 (maio de 2026 já passou).
        val draft = parser.parse("dia das mães almoço")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 5, 9))
    }

    @Test
    fun fimDoMesViraOUltimoDia() {
        // "amanhã no fim do mês": o "amanhã" ganhava em silêncio e entregava 21/08 completo e
        // não-ambíguo. A data certa é o último dia do mês.
        val comAmanha = parser.parse("amanhã no fim do mês às 10h")
        assertThat(comAmanha.localDate).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(comAmanha.localTime).isEqualTo(LocalTime.of(10, 0))

        // Sem o "amanhã", a frase ficava SEM DATA e com o "Fim" no título.
        val semAmanha = parser.parse("no fim do mês pagar conta")
        assertThat(semAmanha.localDate).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(semAmanha.title).isEqualTo("Pagar conta")

        val final = parser.parse("final do mês pagar conta")
        assertThat(final.localDate).isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(final.title).isEqualTo("Pagar conta")
    }

    @Test
    fun inicioEMeioDoMes() {
        // O dia 1 deste mês já passou (hoje é 20/08): vai para 01/09, como o "começo do mês".
        val inicio = parser.parse("início do mês pagar conta")
        assertThat(inicio.localDate).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(inicio.title).isEqualTo("Pagar conta")

        // O dia 15 deste mês já passou: 15/09.
        val meio = parser.parse("meio do mês pagar conta")
        assertThat(meio.localDate).isEqualTo(LocalDate.of(2026, 9, 15))
        assertThat(meio.title).isEqualTo("Pagar conta")

        // O espelho que já funcionava não pode regredir.
        val comeco = parser.parse("no começo do mês pagar conta")
        assertThat(comeco.localDate).isEqualTo(LocalDate.of(2026, 9, 1))
        assertThat(comeco.title).isEqualTo("Pagar conta")
    }

    @Test
    fun diaDoMesQueVemEhDataUnicaNoMesSeguinte() {
        // O "amanhã" ganhava em silêncio (21/08) E uma série MONTHLY nascia por cima. É uma data
        // única no mês seguinte: 25/09, sem recorrência.
        val comAmanha = parser.parse("amanhã dia 25 do mês que vem às 9h")
        assertThat(comAmanha.localDate).isEqualTo(LocalDate.of(2026, 9, 25))
        assertThat(comAmanha.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(comAmanha.recurrence.kind).isEqualTo(RecurrenceKind.NONE)

        val semAmanha = parser.parse("pagar conta dia 25 do mês que vem")
        assertThat(semAmanha.localDate).isEqualTo(LocalDate.of(2026, 9, 25))
        assertThat(semAmanha.recurrence.kind).isEqualTo(RecurrenceKind.NONE)
        assertThat(semAmanha.title).isEqualTo("Pagar conta")
    }

    @Test
    fun todaSemanaEhSerieSemanal() {
        val draft = parser.parse("toda semana limpar a casa")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        // O dia da semana É a asserção que faltava: sem ela, "toda semana na terça" ancorava a
        // série em HOJE (quinta 20/08) e a suíte inteira continuava verde (F12).
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.THURSDAY)
        assertThat(draft.title).isEqualTo("Limpar casa")
    }

    @Test
    fun daquiADoisDiasEDuasSemanas() {
        val doisDias = parser.parse("daqui a dois dias")
        assertThat(doisDias.localDate).isEqualTo(LocalDate.of(2026, 8, 22))

        val duasSemanas = parser.parse("daqui a duas semanas dentista")
        assertThat(duasSemanas.localDate).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(duasSemanas.title).isEqualTo("Dentista")

        val numerico = parser.parse("daqui a 3 dias pagar conta às 9h")
        assertThat(numerico.localDate).isEqualTo(LocalDate.of(2026, 8, 23))
    }

    @Test
    fun intervaloEmDiasNaoViraData() {
        // "de 15 em 15 dias" é intervalo, como o "de 8 em 8 horas": sem data e sem hora, ambíguo
        // para ela confirmar. Antes ficava tudo nulo com o "Dias" no título.
        val draft = parser.parse("de 15 em 15 dias")
        assertThat(draft.localDate).isNull()
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.notes.joinToString()).contains("intervalo")
    }

    @Test
    fun noDiaQuinzeDoMesViraMensal() {
        // B7: "no dia 15 do mês" (com o "do mês") é mensal; antes não virava data nem recorrência.
        val draft = parser.parse("pagar conta no dia 15 do mês")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertThat(draft.recurrence.dayOfMonth).isEqualTo(15)
    }

    // ---- Período do dia: "depois do jantar", "pela manhã", hora falada sem o "às", "em ponto" e
    //      "meio/começo/fim da tarde". Confirmados contra o parser real de main (auditoria 05/10). ----

    @Test
    fun depoisDoJantarComHoraDeUmAOnzeViraNoite() {
        // "amanhã depois do jantar às oito" saía 2026-08-21 08:00 com faltam=[] e conf 0.85: a caixa
        // rápida confirmava sem consultar a IA e a tarefa era agendada de manhã em silêncio.
        val depois = parser.parse("amanhã depois do jantar às oito")
        assertThat(depois.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(depois.localTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(depois.title).doesNotContain("Jantar")

        val antes = parser.parse("amanhã antes do jantar às oito")
        assertThat(antes.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        // 20:00 é o jantar, não "antes" dele — ambíguo, não crava (R7).
        assertThat(antes.ambiguous).isTrue()
        assertThat(antes.localTime!!.hour).isLessThan(20)
    }

    @Test
    fun antesDoJantarComHoraDaTardeNaoViraMadrugada() {
        val draft = parser.parse("amanhã antes do jantar às seis")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(18, 0))
        // O "antes do jantar" é o contexto que desfaz a ambiguidade do "às seis" (06:00 vs 18:00):
        // sem ele no PERIOD_PHRASE, o "seis" ficaria 1–6 e o rascunho viraria ambíguo à toa.
        assertThat(draft.ambiguous).isFalse()

        // Qualquer hora 1–6 dita "antes do jantar" é a tarde (jantar ≈20h): "às três" é 15:00.
        val tres = parser.parse("amanhã antes do jantar às três")
        assertThat(tres.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(tres.ambiguous).isFalse()
    }

    @Test
    fun depoisDoJantarSemHoraNaoInventaHorario() {
        val draft = parser.parse("tomar remédio depois do jantar")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun depoisDoJantarComTarefaNaoDeixaOJantarNoTitulo() {
        val draft = parser.parse("tomar remédio depois do jantar às oito")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun pelaManhaNaoVazaParaOTitulo() {
        val draft = parser.parse("tomar remédio pela manhã")
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
    }

    @Test
    fun pelaManhaAsOitoNaoChamaATarefaDePela() {
        // "amanhã pela manhã às oito" saía com título "Pela", faltam=[] e conf 0.85 — a tarefa se
        // chamava "Pela" e o horário 08:00 era agendado sem escalar.
        val semTarefa = parser.parse("amanhã pela manhã às oito")
        assertThat(semTarefa.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(semTarefa.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(semTarefa.title).doesNotContain("Pela")

        val comTarefa = parser.parse("tomar remédio pela manhã às oito")
        assertThat(comTarefa.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(comTarefa.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun horaFaladaSemAsNaoCravaSemPeriodo() {
        // O D3 reconhecia "duas da tarde" (sem o "às") como hora por compensar a hipótese de que o
        // Vosk derruba o "às" — hipótese nunca medida. Sem o "às" não dá para separar hora de dose
        // ("duas da manhã") nem de data ("25/12 da tarde"); a regra cravava errado com
        // ambiguous=false. Revertida: sem o "às", a hora fica nula e ambígua (escala para a IA).
        val duas = parser.parse("tomar remédio duas da tarde")
        assertThat(duas.localTime).isNull()
        assertThat(duas.ambiguous).isTrue()
        assertThat(duas.missingFields).contains(MissingDraftField.TIME)

        val oito = parser.parse("tomar remédio 8 da manhã")
        assertThat(oito.localTime).isNull()
        assertThat(oito.ambiguous).isTrue()

        // Com o "às" continua resolvendo — isso não pode regredir.
        val comAs = parser.parse("tomar remédio às duas da tarde")
        assertThat(comAs.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(comAs.ambiguous).isFalse()
        assertThat(comAs.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun horaFaladaSemAsContinuaSomandoOPeriodo() {
        // O que já funcionava com o "às" não pode regredir.
        val comAs = parser.parse("tomar remédio às 8 da noite")
        assertThat(comAs.localTime).isEqualTo(LocalTime.of(20, 0))

        val tres = parser.parse("tomar remédio às três da tarde")
        assertThat(tres.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(tres.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun emPontoNaoInventaHoraNemSobraNoTitulo() {
        // "em ponto" é QUALIFICADOR de uma hora que os relógios normais já reconheceram, nunca fonte
        // dela. Com o "às" a hora vem do relógio normal e o "em ponto" é só consumido (título limpo);
        // sem o "às" não há hora reconhecida, e o "em ponto" não cria uma — "três horas em ponto" fica
        // sem hora, como em main, em vez de virar 03:00 por conta própria.
        val comAs = parser.parse("às três em ponto tomar remédio")
        assertThat(comAs.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(comAs.title).isEqualTo("Tomar remédio")

        val comH = parser.parse("amanhã reunião às 9h em ponto")
        assertThat(comH.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(comH.ambiguous).isFalse()
        assertThat(comH.title).isEqualTo("Reunião")

        val semAs = parser.parse("amanhã três horas em ponto")
        assertThat(semAs.localTime).isNull()
    }

    @Test
    fun faixaDoDiaNaoViraTitulo() {
        // "meio da tarde" virava título "Meio" com faltam=[] e conf 0.85 (não escalava).
        val meioTarde = parser.parse("amanhã meio da tarde às quatro")
        assertThat(meioTarde.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(meioTarde.localTime).isEqualTo(LocalTime.of(16, 0))
        assertThat(meioTarde.title).doesNotContain("Meio")

        val meioManha = parser.parse("tomar remédio meio da manhã às oito")
        assertThat(meioManha.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(meioManha.title).isEqualTo("Tomar remédio")

        // "começo da tarde" é a faixa das 13–14h; "às três" já vira 15h pelo período "da tarde" e a
        // hora dita manda — o valor fica na faixa vizinha, não é reescrito para a referência.
        val comecoTarde = parser.parse("tomar remédio começo da tarde às três")
        assertThat(comecoTarde.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(comecoTarde.title).isEqualTo("Tomar remédio")

        // "fim de tarde às cinco" (17h) já está na faixa — a hora dita manda.
        val fimTarde = parser.parse("tomar remédio fim de tarde às cinco")
        assertThat(fimTarde.localTime).isEqualTo(LocalTime.of(17, 0))
        assertThat(fimTarde.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun faixaDoDiaSemHoraNaoInventaHorario() {
        val draft = parser.parse("tomar remédio meio da tarde")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    // ---- Regressões do review do #52: a hora completa, não-ambígua e errada. Cada teste abaixo
    //      falha contra o parser do PR e passa depois do fix. Comportamento de main citado no
    //      comentário de cada um (conferido rodando os dois lado a lado). ----

    @Test
    fun f1EmPontoConservaOPeriodoEOsMinutosDaHoraReconhecida() {
        // O "em ponto" qualifica a hora que os relógios normais acharam, e não interfere nela: o
        // período dito ("da tarde") e o minuto ("e meia") continuam vindo do ramo normal, que já os
        // lia certo. Antes o ramo próprio do "em ponto" relia a frase com a própria regex e perdia
        // os dois — o "da tarde" era descartado e a hora saía sem os 30 min.
        val tarde = parser.parse("amanhã às três em ponto da tarde tomar remédio")
        assertThat(tarde.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(tarde.ambiguous).isFalse()
        assertThat(tarde.title).isEqualTo("Tomar remédio")

        val noite = parser.parse("amanhã às oito em ponto da noite tomar remédio")
        assertThat(noite.localTime).isEqualTo(LocalTime.of(20, 0))

        val meia = parser.parse("amanhã oito e meia em ponto tomar remédio")
        assertThat(meia.localTime).isEqualTo(LocalTime.of(8, 30))

        // Os dois juntos: o "e meia" (minuto) e o período, na mesma frase.
        val juntos = parser.parse("amanhã oito e meia em ponto da noite tomar remédio")
        assertThat(juntos.localTime).isEqualTo(LocalTime.of(20, 30))

        // "três e meia em ponto" sem período: 3h da manhã ou da tarde, como "às três e meia".
        val tresEMeia = parser.parse("amanhã três e meia em ponto")
        assertThat(tresEMeia.localTime).isEqualTo(LocalTime.of(3, 30))
        assertThat(tresEMeia.ambiguous).isTrue()
    }

    @Test
    fun f2DoseContadaNaoViraHora() {
        // "duas de manhã" é a DOSE (duas), não 02:00. A hora da tarde se diz com o artigo
        // ("duas da tarde", de+a); "de manhã" sem artigo é o período/dose. O PR confirmava
        // "amanhã às 02:00" na caixa rápida, sem consultar a IA.
        val duasManha = parser.parse("amanhã tomar duas de manhã")
        assertThat(duasManha.localTime).isNull()
        assertThat(duasManha.ambiguous).isTrue()
        assertThat(duasManha.missingFields).contains(MissingDraftField.TIME)
        assertThat(duasManha.title).isEqualTo("Tomar duas")

        val uma = parser.parse("tomar uma de manhã")
        assertThat(uma.localTime).isNull()
        assertThat(uma.ambiguous).isTrue()

        val tres = parser.parse("tomar três de manhã")
        assertThat(tres.localTime).isNull()
        assertThat(tres.ambiguous).isTrue()

        val comprar = parser.parse("comprar duas de tarde")
        assertThat(comprar.localTime).isNull()
        assertThat(comprar.ambiguous).isTrue()

        // Duas doses na mesma frase não podem colapsar numa hora só.
        val duasDoses = parser.parse("tomar duas de manhã e duas de noite")
        assertThat(duasDoses.localTime).isNull()
        assertThat(duasDoses.ambiguous).isTrue()

        // O artigo "da" é o mesmo da hora ("duas da tarde"), então a dose com artigo não pode
        // virar hora — é por aqui que o R3 passava despercebido.
        val duasDaManha = parser.parse("tomar duas da manhã")
        assertThat(duasDaManha.localTime).isNull()
        assertThat(duasDaManha.ambiguous).isTrue()

        // O contraste que já funcionava: a palavra intermediária salva ("duas gotas").
        val gotas = parser.parse("tomar duas gotas de manhã")
        assertThat(gotas.localTime).isNull()
        assertThat(gotas.ambiguous).isTrue()

        // O caso que a auditoria quer: "às duas da tarde" é 14:00, sem escalar.
        val comAs = parser.parse("tomar remédio às duas da tarde")
        assertThat(comAs.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(comAs.ambiguous).isFalse()
        assertThat(comAs.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun f3DiaDoMesComPeriodoNaoViraHora() {
        // "dia 5 de manhã": o "5" é o dia do mês, não 05:00. O PR substituía a data por "amanhã"
        // e transformava o "5" em hora. Em main, "consulta dia 5 de tarde" era 2026-09-05.
        val amanha = parser.parse("amanhã consulta dia 5 de manhã")
        assertThat(amanha.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(amanha.localTime).isNull()
        assertThat(amanha.ambiguous).isTrue()

        val tarde = parser.parse("consulta dia 5 de tarde")
        assertThat(tarde.localDate).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(tarde.localTime).isNull()
        assertThat(tarde.ambiguous).isTrue()

        // O "da" não pode reabrir a porta: "dia 5 da manhã" também é o dia do mês.
        val da = parser.parse("consulta dia 5 da manhã")
        assertThat(da.localDate).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(da.localTime).isNull()
        assertThat(da.ambiguous).isTrue()
    }

    @Test
    fun f4DepoisDoJantarComHoraDaMadrugadaNaoViraTarde() {
        // "depois do jantar às duas" saía 14:00 não-ambíguo — 14h é antes do jantar. Em main era
        // 02:00 com ambíguo (não confirmava); o PR confirmava errado.
        val duas = parser.parse("amanhã depois do jantar às duas tomar remédio")
        assertThat(duas.localTime).isEqualTo(LocalTime.of(2, 0))
        assertThat(duas.ambiguous).isTrue()

        listOf("três", "quatro", "cinco").forEach { palavra ->
            val draft = parser.parse("amanhã depois do jantar às $palavra tomar remédio")
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.localTime!!.hour).isLessThan(13)
        }
    }

    @Test
    fun f5AntesDoJantarNaoEstouraANoite() {
        // "antes do jantar às dez" saía 22:00 não-ambíguo — 22h é depois do jantar, contradiz o
        // "antes". Em main era 10:00.
        val dez = parser.parse("amanhã antes do jantar às dez tomar remédio")
        assertThat(dez.ambiguous).isTrue()
        assertThat(dez.localTime!!.hour).isLessThan(20)

        val onze = parser.parse("amanhã antes do jantar às onze tomar remédio")
        assertThat(onze.ambiguous).isTrue()

        // O "e meia" (grupo 3) não pode ser descartado: em main era 06:30; o "antes" leva a 18:30.
        val seisEMeia = parser.parse("amanhã antes do jantar às seis e meia tomar remédio")
        assertThat(seisEMeia.localTime).isEqualTo(LocalTime.of(18, 30))
        assertThat(seisEMeia.ambiguous).isFalse()

        // O período EXPLÍCITO vence o marco do jantar: "às seis da manhã" é 06:00, não 18:00.
        val seisManha = parser.parse("amanhã antes do jantar às seis da manhã tomar remédio")
        assertThat(seisManha.localTime).isEqualTo(LocalTime.of(6, 0))
        assertThat(seisManha.ambiguous).isFalse()
    }

    @Test
    fun f6FaixaDoDiaContradizendoAHoraDitaViraAmbigua() {
        // Quando a hora dita e a faixa do dia se contradizem, o correto é ambíguo — não cravar
        // nenhum dos dois em silêncio.
        val vinte = parser.parse("amanhã tomar remédio meio da tarde às vinte")
        assertThat(vinte.ambiguous).isTrue()

        val noite = parser.parse("meio da noite às duas")
        assertThat(noite.ambiguous).isTrue()

        val manha = parser.parse("meio da manhã às onze")
        assertThat(manha.ambiguous).isTrue()

        val oito = parser.parse("amanhã meio da tarde às oito")
        assertThat(oito.ambiguous).isTrue()
    }

    @Test
    fun f7DuasDosesNaMesmaFraseNaoColamNuma() {
        // "duas da tarde e três da noite": uma dose some da agenda. O looksLikeTwoTasks não via
        // hora nem verbo na segunda oração de dose. Ambíguo sozinho não basta: a hora também não
        // pode sobrar, senão a segunda dose ainda some e a primeira é cravada.
        val draft = parser.parse("amanhã tomar duas da tarde e três da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.localTime).isNull()

        // Com uma hora de verdade ("às 22h") a frase tem dia, hora e duas doses: continua ambígua,
        // e a hora não sobra sozinha para a caixa rápida confirmar.
        val comHora = parser.parse("amanhã tomar duas da manhã e três da noite às 22h")
        assertThat(comHora.ambiguous).isTrue()
        assertThat(comHora.localTime).isNull()
    }

    @Test
    fun f8NotaDoAntesDoJantarNaoFalaDepois() {
        // O PeriodHit do jantar devolvia o rótulo fixo "depois do jantar": a nota falava para ela
        // um texto que contradizia o que ela disse.
        val draft = parser.parse("tomar remédio antes do jantar")
        assertThat(draft.notes.joinToString()).contains("antes do jantar")
        assertThat(draft.notes.joinToString()).doesNotContain("depois do jantar")
    }

    @Test
    fun f9DoseContadaNaoSomeDoTitulo() {
        assertThat(parser.parse("tomar duas de manhã").title).isEqualTo("Tomar duas")
        assertThat(parser.parse("comprar duas de tarde").title).isEqualTo("Comprar duas")
    }

    // ---- Terceira rodada do review do #52. A raiz de R1/R2/R3/R6 é o D3 ("duas da tarde" sem o
    //      "às"): a premissa de que o Vosk derruba o "às" nunca foi medida, e a regra que a
    //      compensava crava hora/data errada com ambiguous=false. Foi revertida — na dúvida, ambíguo.
    //      R4/R5/R7 são de outras peças do PR. Cada teste abaixo falha contra o parser do PR. ----

    @Test
    fun r1DataNumericaComPeriodoNaoViraHora() {
        // "consulta dia 25/12 da tarde": o "12" de "25/12" era lido como meio-dia e o dia virava o
        // 25 de agosto. A data correta é 25 de dezembro; o período "da tarde" sozinho não é hora.
        val dia25 = parser.parse("consulta dia 25/12 da tarde")
        assertThat(dia25.localDate).isEqualTo(LocalDate.of(2026, 12, 25))
        assertThat(dia25.localTime).isNull()
        assertThat(dia25.ambiguous).isTrue()

        val semDia = parser.parse("consulta 25/12 da tarde")
        assertThat(semDia.localDate).isEqualTo(LocalDate.of(2026, 12, 25))
        assertThat(semDia.localTime).isNull()
        assertThat(semDia.ambiguous).isTrue()

        val cincoNove = parser.parse("consulta 5/9 da tarde")
        assertThat(cincoNove.localDate).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(cincoNove.localTime).isNull()
        assertThat(cincoNove.ambiguous).isTrue()

        val prova = parser.parse("prova 10/10 da noite")
        assertThat(prova.localDate).isEqualTo(LocalDate.of(2026, 10, 10))
        assertThat(prova.localTime).isNull()
        assertThat(prova.ambiguous).isTrue()

        val viagem = parser.parse("viagem 1/1 da manhã")
        assertThat(viagem.localDate).isEqualTo(LocalDate.of(2027, 1, 1))
        assertThat(viagem.localTime).isNull()
        assertThat(viagem.ambiguous).isTrue()
    }

    @Test
    fun r2RecorrenciaComNumeroEPeriodoNaoViraHora() {
        // "todo dia 5 da tarde": o "5" é o dia (recorrência diária), não 05:00. O PR cravava
        // 2026-08-20 17:00 com ambiguous=false — a caixa rápida confirmava sem consultar a IA.
        val diario = parser.parse("todo dia 5 da tarde")
        assertThat(diario.localTime).isNull()
        assertThat(diario.ambiguous).isTrue()

        val semanal = parser.parse("toda semana 5 da tarde")
        assertThat(semanal.localTime).isNull()
        assertThat(semanal.ambiguous).isTrue()

        val remedio = parser.parse("tomar remédio todo dia 5 da tarde")
        assertThat(remedio.localTime).isNull()
        assertThat(remedio.ambiguous).isTrue()

        // O "dia 5" sem o "todo" já era tratado: não pode regredir.
        val dia5 = parser.parse("consulta dia 5 da tarde")
        assertThat(dia5.localDate).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(dia5.localTime).isNull()
        assertThat(dia5.ambiguous).isTrue()
    }

    @Test
    fun r3DoseComArtigoNaoViraHora() {
        // "tomar duas da manhã" é a DOSE (duas), não 02:00. O artigo "da" não separa dose de hora:
        // "duas da tarde" (hora) e "duas da manhã" (dose) têm o mesmo artigo. O PR cravava 02:00
        // com ambiguous=false e título "Tomar".
        val duasManha = parser.parse("tomar duas da manhã")
        assertThat(duasManha.localTime).isNull()
        assertThat(duasManha.ambiguous).isTrue()
        assertThat(duasManha.title).isEqualTo("Tomar duas")

        listOf("tomar duas da tarde", "tomar duas da noite", "tomar 2 da manhã").forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
        }
    }

    @Test
    fun r4EmPontoComSegundaHoraNaoEscolheAPrimeira() {
        // "às 8 em ponto e às 20h": o ramo do "em ponto" retornava antes do guard de múltiplos
        // relógios, então a segunda hora nunca era vista — 08:00 com cara de certeza. Duas horas
        // na mesma frase são ambíguas, como no main.
        val remedio = parser.parse("tomar remédio às 8 em ponto e às 20h")
        assertThat(remedio.ambiguous).isTrue()
        assertThat(remedio.localTime).isNull()

        val medico = parser.parse("marcar médico às 10 em ponto e dentista às 15h")
        assertThat(medico.ambiguous).isTrue()
        assertThat(medico.localTime).isNull()
    }

    @Test
    fun r5PeriodoExplicitoVenceOMarcoDoJantar() {
        // "antes do jantar às seis da manhã": o marco do jantar sobrescrevia o período explícito e
        // dava 18:00, ignorando o "da manhã". O que ela disse manda: 06:00.
        val seis = parser.parse("antes do jantar às seis da manhã")
        assertThat(seis.localTime).isEqualTo(LocalTime.of(6, 0))
        assertThat(seis.ambiguous).isFalse()

        val seisEMeia = parser.parse("antes do jantar às seis e meia da manhã")
        assertThat(seisEMeia.localTime).isEqualTo(LocalTime.of(6, 30))
        assertThat(seisEMeia.ambiguous).isFalse()

        val oito = parser.parse("depois do jantar às oito da manhã")
        assertThat(oito.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(oito.ambiguous).isFalse()
    }

    @Test
    fun r6DuasDosesNaMesmaFraseNaoDeixamHora() {
        // "tomar duas da manhã e duas da noite": a segunda dose sumia e sobrava uma hora completa
        // (02:00). Sem hora nenhuma e ambíguo, ela confirma em vez de a agenda comer uma dose.
        val draft = parser.parse("tomar duas da manhã e duas da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
    }

    @Test
    fun r7AntesDoJantarNaoAceitaHoraDepoisDoJantar() {
        // "amanhã antes do jantar às oito": o jantar é ≈20h, então 20:00 é o jantar, não "antes"
        // dele. A regra antiga cravava 20:00 e o teste afirmava isso. Ambíguo, para ela confirmar.
        val oito = parser.parse("amanhã antes do jantar às oito")
        assertThat(oito.ambiguous).isTrue()
        assertThat(oito.localTime!!.hour).isLessThan(20)
    }

    @Test
    fun r8AntesDoJantarSemAsNaoInventaHora() {
        // O ramo "antes do jantar seis" (sem o "às") existia para compensar a hipótese de que o
        // Vosk derruba o "às" — a mesma premissa não medida do D3. Sem o "às" não há hora: ambíguo.
        val draft = parser.parse("tomar remédio antes do jantar seis")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
    }

    @Test
    fun r11PelaMadrugadaComHoraCertaNaoFicaAmbigua() {
        // "pela madrugada às três": o período dito desfaz a ambiguidade de 03:00 vs 15:00, como o
        // "pela manhã". O valor (03:00) está certo; não é regressão.
        val draft = parser.parse("tomar remédio pela madrugada às três")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    // ---- Quarta rodada do review do #52 (o ramo EM_PONTO_CLOCK). Cada teste abaixo falha contra o
    //      parser do PR e passa depois do fix. ----

    @Test
    fun p1DiaDoMesEmPontoNaoViraHora() {
        // "amanhã reunião dia 12 em ponto": o número do "dia 12" é o DIA do mês. O ramo do "em ponto"
        // roda em extractTime, que vem ANTES de extractDate, e consumia o número — a data sumia e a
        // hora saía inventada (12:00) com ambiguous=false, então a caixa rápida confirmava calado.
        val doze = parser.parse("amanhã reunião dia 12 em ponto")
        assertThat(doze.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(doze.localTime).isNull()

        val quinze = parser.parse("quinta prova dia 15 em ponto")
        assertThat(quinze.localDate).isEqualTo(LocalDate.of(2026, 9, 15))
        assertThat(quinze.localTime).isNull()

        val vinte = parser.parse("consulta dia 20 em ponto")
        assertThat(vinte.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(vinte.localTime).isNull()

        // A hora com o "h" continua valendo: o "9h" não é dia do mês nenhum.
        val comH = parser.parse("amanhã reunião às 9h em ponto")
        assertThat(comH.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(comH.ambiguous).isFalse()
    }

    @Test
    fun p1bEmPontoComSegundaHoraNaoConfirma() {
        // "amanhã oito em ponto e nove tomar remédio": duas horas na mesma frase ("oito" e "nove").
        // O ramo antigo do "em ponto" cravava 08:00 com ambiguous=false — o mesmo modo de falha do
        // R4, e a caixa rápida confirmava calado. Agora ele não é fonte de hora nenhuma, então "oito
        // em ponto" sozinho não vira hora e a segunda hora (por extenso OU em dígito) não é engolida.
        //
        // O dígito é o caso que escapava: o guard da rodada anterior só listava as formas por extenso
        // (`SEGUNDA_HORA_EXTENSO` = `WORD_HOUR_ALT`), então "e 9", "e 10", "e 12", "e 09", "e 7" e
        // "e 30" passavam por ele e o ramo confirmava 08:00. Por isso a lista tem os dois formatos.
        listOf(
            "amanhã oito em ponto e nove tomar remédio",
            "amanhã tomar remédio oito em ponto e dez",
            "amanhã oito em ponto e dez tomar remédio",
            "amanhã oito em ponto e 9 tomar remédio",
            "amanhã oito em ponto e 10 tomar remédio",
            "amanhã oito em ponto e 12 tomar remédio",
            "amanhã oito em ponto e 09 tomar remédio",
            "amanhã oito em ponto e 7 tomar remédio",
            "amanhã oito em ponto e 30 tomar remédio",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isFalse()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    @Test
    fun p1cDataNumericaEmPontoNaoDeslocaAData() {
        // Com `NN/MM` o ramo antigo consumia o PRIMEIRO número como hora e a data se perdia ou
        // deslocava: "pagamento dia 05/12 em ponto" virava 2026-09-05 12:00 (setembro em vez de
        // dezembro, com hora inventada) e "prova dia 10/10 em ponto" virava 2026-09-10 10:00. Data
        // errada e hora inventada, as duas com ambiguous=false — o pior modo de falha do app.
        val setembro = parser.parse("consulta dia 5/9 em ponto")
        assertThat(setembro.localDate).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(setembro.localTime).isNull()

        val dezembro = parser.parse("pagamento dia 05/12 em ponto")
        assertThat(dezembro.localDate).isEqualTo(LocalDate.of(2026, 12, 5))
        assertThat(dezembro.localTime).isNull()

        val outubro = parser.parse("prova dia 10/10 em ponto")
        assertThat(outubro.localDate).isEqualTo(LocalDate.of(2026, 10, 10))
        assertThat(outubro.localTime).isNull()

        // Com "amanhã" a data já vinha certa; o que se perdia era a hora inventada (12:00).
        val amanha = parser.parse("amanhã consulta 25/12 em ponto")
        assertThat(amanha.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(amanha.localTime).isNull()
    }

    @Test
    fun p2EmPontoComQuantidadeNaoViraHora() {
        // "amanhã oito em ponto e três comprimidos": o "e <quantidade>" é dose, não segunda hora. O
        // ramo antigo lia o "três" como segunda hora e marcava `ambiguous = true` — o lado seguro,
        // mas uma afirmação falsa sobre a frase ("Há mais de um horário"). Sem ramo próprio, não há
        // segunda hora a ver: o rascunho fica com a data, sem horário e sem ambiguidade, e escala
        // porque falta a hora. Por isso o teste prende `ambiguous`, e não só o horário nulo.
        listOf(
            "amanhã oito em ponto e três comprimidos",
            "amanhã oito em ponto e duas gotas",
            "amanhã oito em ponto e uma colher",
            "amanhã oito em ponto e vinte minutos",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.missingFields).contains(MissingDraftField.TIME)
            assertThat(draft.ambiguous).isFalse()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    @Test
    fun p5EmPontoNaoSobraNoTituloDaHoraComH() {
        // O comentário do ramo antigo dizia que ele consumia o "em ponto" para não sobrar no título,
        // mas a regex exigia espaço antes do "h" (`\s+h`) e nunca casava "9h em ponto"/"20h em ponto":
        // o título saía "Reunião ponto". O qualificador não casa a hora, então não erra o "h".
        val comH = parser.parse("amanhã reunião às 9h em ponto")
        assertThat(comH.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(comH.title).isEqualTo("Reunião")

        val vinte = parser.parse("amanhã reunião às 20h em ponto")
        assertThat(vinte.localTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(vinte.title).isEqualTo("Reunião")
    }

    @Test
    fun p2AntesDoJantarAs20hNaoConfirma() {
        // O guard do "antes do jantar" tem dois disjuntos: hora em 7..11 e hora == 20. O teste do
        // R7 só exercitava "às oito" (que cai em 7..11), então remover `|| localTime.hour == 20`
        // passava a suíte inteira. O "às 20h" prende o outro disjunto: 20:00 É o jantar, não
        // "antes" dele.
        val vinte = parser.parse("amanhã antes do jantar às 20h")
        assertThat(vinte.ambiguous).isTrue()
        assertThat(vinte.localTime).isEqualTo(LocalTime.of(20, 0))
    }

    @Test
    fun listaNaoPodeRegredirDaAuditoria() {
        // Seções "O que já está correto — não mexer" e "Falsos positivos que qualquer conserto
        // precisa respeitar" do documento de auditoria da fala em .context/docs (a rodada de
        // 05/10/2026). O caminho exato ficou fora daqui de propósito: quem revisa o PR não
        // consegue abrir um arquivo que não está na árvore.
        val serie = parser.parse("toda terça e quinta natação às 18h")
        assertThat(serie.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(serie.localTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(serie.ambiguous).isFalse()
        assertThat(serie.title).isEqualTo("Natação")

        assertThat(parser.parse("hoje à noite às nove").localTime).isEqualTo(LocalTime.of(21, 0))
        assertThat(parser.parse("às 8 da noite").localTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(parser.parse("às 3 e meia da noite").localTime).isEqualTo(LocalTime.of(3, 30))
        assertThat(parser.parse("meio-dia e meia").localTime).isEqualTo(LocalTime.of(12, 30))

        val intervalo = parser.parse("de 8 em 8 horas")
        assertThat(intervalo.localTime).isNull()
        assertThat(intervalo.ambiguous).isTrue()

        val amanhaManha = parser.parse("amanhã de manhã")
        assertThat(amanhaManha.localTime).isNull()
        assertThat(amanhaManha.ambiguous).isTrue()

        val almoco = parser.parse("depois do almoço")
        assertThat(almoco.localTime).isNull()
        assertThat(almoco.ambiguous).isTrue()
    }

    // ---- Regressões do PR #55 (revisão independente de 06/10/2026): data/hora errada, completa e não-ambígua ----

    @Test
    fun todaSemanaComDiaDaSemanaAncoraNoDiaDito() {
        // F1: o bloco de "toda semana" rodava ANTES do dia da semana e ancorava a série em HOJE
        // (quinta 20/08), não na terça — a caixa rápida confirmava o dia errado em silêncio.
        val limpar = parser.parse("toda semana na terça limpar a casa às 8h")
        assertThat(limpar.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(limpar.recurrence.weekDays).containsExactly(DayOfWeek.TUESDAY)
        assertThat(limpar.localDate).isEqualTo(LocalDate.of(2026, 8, 25))
        assertThat(limpar.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.TUESDAY)
        assertThat(limpar.localTime).isEqualTo(LocalTime.of(8, 0))

        val natacao = parser.parse("toda semana na terça natação às 18h")
        assertThat(natacao.localDate).isEqualTo(LocalDate.of(2026, 8, 25))
        assertThat(natacao.localTime).isEqualTo(LocalTime.of(18, 0))
    }

    @Test
    fun relativoEmDiasNaoFabricaHora() {
        // F2: "daqui a duas semanas ... às 9h" produzia a hora do relógio de agora (10:00), não 9h.
        val comHora = parser.parse("daqui a duas semanas dentista às 9h")
        assertThat(comHora.localDate).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(comHora.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(comHora.ambiguous).isFalse()

        // Sem hora dita, escala (não inventa a hora de agora).
        val semHora = parser.parse("daqui a duas semanas dentista")
        assertThat(semHora.localDate).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(semHora.localTime).isNull()
        assertThat(semHora.missingFields).contains(MissingDraftField.TIME)
    }

    @Test
    fun diaDasMaesEDosPaisNaoPulamNemVoltamNoTempo() {
        // F3: o corte por dia-do-mês (dia > 10 / dia > 9) não corresponde ao 2º domingo real.
        // Em 13/05/2028 (véspera do Dia das Mães de 2028, 14/05) o corte antigo pulava para 2029.
        val vespera = parserEm(LocalDateTime.of(2028, 5, 13, 10, 0))
        assertThat(vespera.parse("dia das mães almoço").localDate).isEqualTo(LocalDate.of(2028, 5, 14))

        // Em 10/05/2027 (o dia seguinte ao Dia das Mães de 2027, 09/05) o corte antigo devolvia
        // 09/05/2027 — uma data no PASSADO.
        val depois = parserEm(LocalDateTime.of(2027, 5, 10, 10, 0))
        assertThat(depois.parse("dia das mães almoço").localDate).isEqualTo(LocalDate.of(2028, 5, 14))

        // 2º domingo de agosto de 2026 (09/08) já passou; o próximo é 08/08/2027.
        assertThat(parser.parse("dia dos pais almoço").localDate).isEqualTo(LocalDate.of(2027, 8, 8))

        // Em 09/08/2027 (dia seguinte ao Dia dos Pais de 2027, 08/08) o corte antigo devolvia
        // 08/08/2027 — outra data no passado.
        val depoisPais = parserEm(LocalDateTime.of(2027, 8, 9, 10, 0))
        assertThat(depoisPais.parse("dia dos pais almoço").localDate).isEqualTo(LocalDate.of(2028, 8, 13))
    }

    @Test
    fun substantivoComumNaoViraDataNomeada() {
        // F4: "natal" e "cinzas" casavam como substantivo comum e viravam 25/12 e a Quarta-feira
        // de Cinzas, completos e não-ambíguos, sem a IA consultar.
        val terraNatal = parser.parse("voltar para minha terra natal às 10h")
        assertThat(terraNatal.localDate).isNull()
        assertThat(terraNatal.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(terraNatal.missingFields).contains(MissingDraftField.DATE)

        val cinzas = parser.parse("limpar as cinzas da churrasqueira às 10h")
        assertThat(cinzas.localDate).isNull()
        assertThat(cinzas.missingFields).contains(MissingDraftField.DATE)

        // As formas de data continuam valendo.
        assertThat(parser.parse("no Natal almoço às 13h").localDate).isEqualTo(LocalDate.of(2026, 12, 25))
        assertThat(parser.parse("dia de Natal almoço às 13h").localDate).isEqualTo(LocalDate.of(2026, 12, 25))
    }

    @Test
    fun fimEMeioDoMesQueVemVaoParaOMesSeguinte() {
        // F5: o "que vem" era ignorado e a borda voltava o mês ATUAL.
        val fim = parser.parse("fim do mês que vem pagar conta")
        assertThat(fim.localDate).isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(fim.title).isEqualTo("Pagar conta")

        val meio = parser.parse("meio do mês que vem pagar conta")
        assertThat(meio.localDate).isEqualTo(LocalDate.of(2026, 9, 15))

        val comeco = parser.parse("começo do mês que vem pagar conta")
        assertThat(comeco.localDate).isEqualTo(LocalDate.of(2026, 9, 1))

        // Sem o "que vem", o espelho que já funcionava não pode regredir.
        assertThat(parser.parse("fim do mês pagar conta").localDate).isEqualTo(LocalDate.of(2026, 8, 31))
    }

    @Test
    fun todoDiaVinteECincoDoMesQueVemContinuaMensal() {
        // F6: a exclusão "(?!que vem)" da regex mensal derrubava a série; a frase virava DIÁRIA
        // e o 25 sumia.
        val draft = parser.parse("todo dia 25 do mês que vem caminhar")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.MONTHLY)
        assertThat(draft.recurrence.dayOfMonth).isEqualTo(25)
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 25))
    }

    @Test
    fun sextaSantaSemFeiraNaoViraASextaDestaSemana() {
        // F7: a regex exigia "feira", então "sexta santa" caía no dia da semana comum e devolvia
        // a PRÓXIMA sexta (21/08/2026) — o mesmo defeito D1 que este PR existia para matar.
        val draft = parser.parse("sexta santa missa às 15h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2027, 3, 26))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(draft.title).isEqualTo("Missa")
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun todaSemanaQueVemComecaNaProximaSemana() {
        // F8: o "que vem" era engolido e a série começava hoje. A hora é FUTURA (18h) de propósito:
        // com hora já passada (8h) o guard de horário empurrava a série para a semana seguinte por
        // acaso e o teste passava com o bug do P0-1 intacto.
        val draft = parser.parse("toda semana que vem limpar a casa às 18h")
        assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(draft.title).isEqualTo("Limpar casa")
    }

    @Test
    fun emDuasSemanasEhRelativoEmDias() {
        // F9: "em duas semanas" ficava pela metade (sem data) com a hora fabricada.
        val draft = parser.parse("em duas semanas dentista")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(draft.localTime).isNull()
        assertThat(draft.title).isEqualTo("Dentista")
    }

    @Test
    fun intervaloComHoraDitaNaoPedeAHoraDeNovo() {
        // F10: a nota do intervalo dizia "diga o horário da primeira vez" na mesma frase em que
        // ela tinha dito "às 9h" — a nota contradizia a hora declarada.
        val draft = parser.parse("de 15 em 15 dias às 9h")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.localDate).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.notes.joinToString()).contains("intervalo")
        assertThat(draft.notes.joinToString()).doesNotContain("Diga o horário")
    }

    @Test
    fun feiraDeMercadoNaoSomeDoTitulo() {
        // A: "feira" (mercado) não é o sufixo de um dia da semana. O `\bfeiras?\b` comia qualquer
        // "feira", então "ir na feira sábado" virava "Ir" — data e hora certas, sem ambiguidade,
        // e a caixa rápida confirmava o nome errado sem consultar a IA.
        val irNaFeira = parser.parse("ir na feira sábado às 8h")
        assertThat(irNaFeira.localDate).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(irNaFeira.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(irNaFeira.title.lowercase()).contains("feira")

        val comprar = parser.parse("comprar na feira sexta às 8h")
        assertThat(comprar.title.lowercase()).contains("feira")

        // O sufixo do dia da semana continua saindo.
        assertThat(parser.parse("sexta-feira dentista às 8h").title).isEqualTo("Dentista")
        assertThat(parser.parse("quarta-feira de cinzas missa às 19h").title).isEqualTo("Missa")
    }

    // ---- Regressões NOVAS da 2ª revisão (P0-1 a P2-8) ----

    @Test
    fun todaSemanaQueVemSemDiaComHoraFuturaNaoAncoraHoje() {
        // P0-1: sem dia da semana o early-return montava a série sem propagar o `nextWeek` — com
        // hora FUTURA (18h) a série ancorava HOJE (20/08), não na semana que vem (27/08). A caixa
        // rápida confirmava hoje em silêncio. Com 8h o guard de horário escondia o defeito.
        val limpar = parser.parse("toda semana que vem limpar a casa às 18h")
        assertThat(limpar.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(limpar.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(limpar.localTime).isEqualTo(LocalTime.of(18, 0))

        val natacao = parser.parse("toda semana que vem natação às 18h")
        assertThat(natacao.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(natacao.localTime).isEqualTo(LocalTime.of(18, 0))

        // Sem hora dita, também vale a semana que vem.
        val semHora = parser.parse("toda semana que vem natação")
        assertThat(semHora.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
    }

    @Test
    fun sextaFeiraSemHifenNaoComeOTitulo() {
        // P0-3: em "na sexta feira dentista" o token antes de "feira" é a preposição, então o guard
        // antigo removia o "Feira" e o título ficava "Feira dentista". O "feira" colado ao dia já
        // saiu junto com o dia; o que sobra é o substantivo? Não: aqui é o sufixo do dia.
        val na = parser.parse("na sexta feira dentista às 8h")
        assertThat(na.title).isEqualTo("Dentista")
        assertThat(na.localDate).isEqualTo(LocalDate.of(2026, 8, 21))

        val de = parser.parse("de sexta feira dentista às 8h")
        assertThat(de.title).isEqualTo("Dentista")

        val quarta = parser.parse("na quarta feira dentista às 8h")
        assertThat(quarta.title).isEqualTo("Dentista")
        assertThat(quarta.localDate).isEqualTo(LocalDate.of(2026, 8, 26))

        // "toda semana na sexta feira": a série no dia dito, sem "Feira"/"Semana" no título.
        val serie = parser.parse("toda semana na sexta feira natação às 18h")
        assertThat(serie.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
        assertThat(serie.recurrence.weekDays).containsExactly(DayOfWeek.FRIDAY)
        assertThat(serie.title).isEqualTo("Natação")

        // O hífen já funcionava e não pode regredir.
        assertThat(parser.parse("na sexta-feira dentista às 8h").title).isEqualTo("Dentista")
    }

    @Test
    fun cinzasNuNoInicioDaFraseNaoViraData() {
        // P0-4: `before` é "" no início da frase e "" ∈ DATE_DETERMINERS, então "cinzas da
        // churrasqueira" virava a Quarta-feira de Cinzas (10/02/2027) completa e não-ambígua.
        listOf("cinzas da churrasqueira", "cinzas do fogão limpar", "cinzas").forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localDate).isNull()
            assertThat(draft.missingFields).contains(MissingDraftField.DATE)
        }

        // A forma de data (com o determinante/preposição) continua valendo.
        assertThat(parser.parse("quarta-feira de cinzas missa às 19h").localDate)
            .isEqualTo(LocalDate.of(2027, 2, 10))

        // "limpar as cinzas" já estava certo e não pode regredir.
        assertThat(parser.parse("limpar as cinzas da churrasqueira às 10h").localDate).isNull()
    }

    @Test
    fun festaIsoladaNaoZeraOTitulo() {
        // P0-5: "natal" isolado virava data 25/12 com title=''; o main devolvia title='Natal'.
        val natal = parser.parse("natal")
        assertThat(natal.localDate).isEqualTo(LocalDate.of(2026, 12, 25))
        assertThat(natal.title).isEqualTo("Natal")

        val finados = parser.parse("finados")
        assertThat(finados.localDate).isEqualTo(LocalDate.of(2026, 11, 2))
        assertThat(finados.title).isEqualTo("Finados")
    }

    @Test
    fun anoDitoNaDataNomeadaVence() {
        // P0-6: "no Natal de 2027" ignorava o ano e devolvia 2026 (relógio 20/08/2026).
        val natal2027 = parser.parse("no Natal de 2027 almoço")
        assertThat(natal2027.localDate).isEqualTo(LocalDate.of(2027, 12, 25))

        val natal2026 = parser.parse("no Natal de 2026 almoço")
        assertThat(natal2026.localDate).isEqualTo(LocalDate.of(2026, 12, 25))

        // Relógio em 2027: "no Natal de 2026" tem que dar 2026 (já passou), não 2027.
        val em2027 = parserEm(LocalDateTime.of(2027, 5, 10, 10, 0))
        assertThat(em2027.parse("no Natal de 2026 almoço").localDate).isEqualTo(LocalDate.of(2026, 12, 25))
    }

    @Test
    fun proximoMesNasBordasVaiParaOMesSeguinte() {
        // P1-7: o PR declara cobrir "próximo mês" mas só MONTH_START/MIDDLE/END + "que vem"
        // casavam. "fim do próximo mês" ficava sem data e o "Fim próximo" ia para o título.
        val fim = parser.parse("fim do próximo mês pagar conta")
        assertThat(fim.localDate).isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(fim.title).isEqualTo("Pagar conta")

        val meio = parser.parse("meio do próximo mês pagar conta")
        assertThat(meio.localDate).isEqualTo(LocalDate.of(2026, 9, 15))

        val comeco = parser.parse("começo do próximo mês pagar conta")
        assertThat(comeco.localDate).isEqualTo(LocalDate.of(2026, 9, 1))

        val noFinal = parser.parse("no final do próximo mês pagar conta")
        assertThat(noFinal.localDate).isEqualTo(LocalDate.of(2026, 9, 30))

        val noInicio = parser.parse("no início do próximo mês pagar conta")
        assertThat(noInicio.localDate).isEqualTo(LocalDate.of(2026, 9, 1))

        // "mês que vem" na mesma forma.
        assertThat(parser.parse("fim do mês que vem pagar conta").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 30))
    }

    @Test
    fun daquiDuasSemanasEMeiaContaOsTresDias() {
        // P2-8: o "e meia" depois de "semanas" sumia e a data saía 7 dias antes (03/09 em vez de
        // 06/09). Meia semana é 3 dias.
        val draft = parser.parse("daqui a duas semanas e meia dentista")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 9, 6))
        assertThat(draft.title).isEqualTo("Dentista")

        // O espelho que já funcionava não pode regredir.
        assertThat(parser.parse("daqui a duas semanas dentista").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 3))
    }

    @Test
    fun listaNaoPodeRegredirDoReview() {
        // Lista literal de "não pode regredir" da revisão do PR #55.
        val serie = parser.parse("toda terça e quinta natação às 18h")
        assertThat(serie.localTime).isEqualTo(LocalTime.of(18, 0))
        assertThat(serie.ambiguous).isFalse()

        assertThat(parser.parse("hoje à noite às nove").localTime).isEqualTo(LocalTime.of(21, 0))
        assertThat(parser.parse("às 8 da noite").localTime).isEqualTo(LocalTime.of(20, 0))
        assertThat(parser.parse("às 3 e meia da noite").localTime).isEqualTo(LocalTime.of(3, 30))
        assertThat(parser.parse("meio-dia e meia").localTime).isEqualTo(LocalTime.of(12, 30))

        val intervaloHoras = parser.parse("de 8 em 8 horas")
        assertThat(intervaloHoras.localTime).isNull()
        assertThat(intervaloHoras.ambiguous).isTrue()

        assertThat(parser.parse("amanhã de manhã").ambiguous).isTrue()
        assertThat(parser.parse("depois do almoço").ambiguous).isTrue()

        val intervaloDuasHoras = parser.parse("de duas em duas horas")
        assertThat(intervaloDuasHoras.localTime).isNull()
        assertThat(intervaloDuasHoras.ambiguous).isTrue()
    }

    // ---- Regressões da 3ª revisão (P0-1 a P2-5): a borda de mês engolia o dia nomeado ----

    @Test
    fun bordaDeMesComDiaNomeadoCaiNoDiaDito() {
        // P0-1: o ramo `monthEdge` rodava ANTES do bloco de dia da semana e devolvia a borda crua.
        // "no fim do mês na sexta" virava 31/08 — uma SEGUNDA —, completo e não-ambíguo: a caixa
        // rápida mostrava 31/08, ela tocava Salvar, e o título ainda dizia "Sexta pagar conta".
        val fimComSexta = parser.parse("no fim do mês na sexta pagar conta às 10h")
        assertThat(fimComSexta.localDate).isEqualTo(LocalDate.of(2026, 8, 28))
        assertThat(fimComSexta.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.FRIDAY)
        assertThat(fimComSexta.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(fimComSexta.title).isEqualTo("Pagar conta")

        // A borda e o dia vêm em qualquer ordem, e o dia escolhe dentro do mês da borda.
        val domingoPrimeiro = parser.parse("domingo no fim do mês aniversário às 15h")
        assertThat(domingoPrimeiro.localDate).isEqualTo(LocalDate.of(2026, 8, 30))
        assertThat(domingoPrimeiro.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.SUNDAY)

        // "meio do mês" (dia 15) já passou: a borda vai para setembro e a sexta é a de setembro.
        val meioComSexta = parser.parse("sexta no meio do mês almoço às 12h")
        assertThat(meioComSexta.localDate).isEqualTo(LocalDate.of(2026, 9, 11))
        assertThat(meioComSexta.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.FRIDAY)

        val queVemComQuinta = parser.parse("quinta no fim do mês que vem jantar às 20h")
        assertThat(queVemComQuinta.localDate).isEqualTo(LocalDate.of(2026, 9, 24))
        assertThat(queVemComQuinta.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.THURSDAY)
    }

    @Test
    fun bordaDeMesSemDiaNomeadoNaoMuda() {
        // O caminho que já funcionava não pode regredir com o consumo do dia nomeado.
        assertThat(parser.parse("no fim do mês pagar conta").localDate)
            .isEqualTo(LocalDate.of(2026, 8, 31))
        assertThat(parser.parse("fim do mês que vem pagar conta").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(parser.parse("meio do mês que vem pagar conta").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 15))
        assertThat(parser.parse("fim do próximo mês pagar conta").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 30))
    }

    @Test
    fun queVemADistanciaNaoDeslocaABorda() {
        // P1-2: `NEXT_MONTH_TAIL` casava o primeiro "que vem" em QUALQUER ponto depois da borda,
        // sem âncora. O "que vem" de outra oração deslocava a data em silêncio para o mês seguinte.
        val comOracao = parser.parse("no fim do mês pagar conta às 10h e o que vem depois a gente vê")
        assertThat(comOracao.localDate).isEqualTo(LocalDate.of(2026, 8, 31))

        val antes = parser.parse("fim do mês às 10h, me diz o que vem antes")
        assertThat(antes.localDate).isEqualTo(LocalDate.of(2026, 8, 31))

        // Colado à borda, o "que vem" continua deslocando.
        assertThat(parser.parse("fim do mês que vem pagar conta").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 30))
    }

    @Test
    fun mesPassadoVaiParaOmesAnterior() {
        // P1-2 (2ª face): "passado" era ignorado e a borda caía no mês FUTURO — o oposto do dito.
        val passado = parser.parse("no fim do mês passado pagar conta às 10h")
        assertThat(passado.localDate).isEqualTo(LocalDate.of(2026, 7, 31))
        assertThat(passado.title).isEqualTo("Pagar conta")
    }

    @Test
    fun feiraComoSubstantivoNaoSomeDoTitulo() {
        // P2-3: o `[-\s]?feira` das WEEKDAY_PATTERNS engolia o substantivo colado ao dia — o guard
        // `stripFeiraSuffix` nunca via o token que ele existe para proteger. "sexta feira de
        // ciências" perdia o "feira"; "sábado feira às 8h" ficava com título vazio.
        val ciencias = parser.parse("sexta feira de ciências às 8h")
        assertThat(ciencias.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(ciencias.title.lowercase()).contains("feira")
        assertThat(ciencias.title.lowercase()).contains("ciências".lowercase().take(5))

        val sabadoFeira = parser.parse("sábado feira às 8h")
        assertThat(sabadoFeira.localDate).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(sabadoFeira.title).isEqualTo("Feira")

        // O sufixo do dia (segunda a sexta) continua saindo.
        assertThat(parser.parse("na sexta feira dentista às 8h").title).isEqualTo("Dentista")
        assertThat(parser.parse("sexta-feira dentista às 8h").title).isEqualTo("Dentista")
        assertThat(parser.parse("toda semana na sexta feira natação às 18h").title).isEqualTo("Natação")
    }

    @Test
    fun cinzasComDeterminanteTambemEhSubstantivo() {
        // P2-4: o guard novo só mordia quando `before` era vazio; com um determinante antes, o
        // substantivo comum passava. "no cinzas" virava 10/02/2027 completo e não-ambíguo.
        listOf("no cinzas", "na cinzas", "no cinzas missa às 19h").forEach { frase ->
            assertThat(parser.parse(frase).localDate).isNull()
        }

        // A forma de data continua valendo.
        assertThat(parser.parse("quarta-feira de cinzas missa às 19h").localDate)
            .isEqualTo(LocalDate.of(2027, 2, 10))
    }

    @Test
    fun meiaSemanaSomaAMeiaEDistingueDeUmNumeroFixo() {
        // P2-5: o teste antigo prendia só o VALOR (06/09); um `MEIA_SEMANA_TAIL` que somasse 3 dias
        // sempre continuaria verde. Aqui o par COM e SEM o "e meia" prende o mecanismo: a meia
        // semana só vale quando dita, e o mesmo deslocamento aparece em outra unidade (1 semana).
        assertThat(parser.parse("daqui a duas semanas dentista").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(parser.parse("daqui a duas semanas e meia dentista").localDate)
            .isEqualTo(LocalDate.of(2026, 9, 6))

        assertThat(parser.parse("daqui a uma semana dentista").localDate)
            .isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(parser.parse("daqui a uma semana e meia dentista").localDate)
            .isEqualTo(LocalDate.of(2026, 8, 30))
    }

    /**
     * O "em ponto" qualifica uma hora que o parser JÁ reconheceu — inclusive quando a hora vem
     * escrita por extenso ("meio-dia", "meia-noite"), não só por número.
     *
     * A regra da casa é `emPontoNaoInventaHoraNemSobraNoTitulo`: sem hora reconhecida o qualificador
     * não cria uma. Mas o inverso também vale, e era o buraco: com hora reconhecida ele tem de sair
     * do texto que sobra para o título. Os ramos do "meio-dia" e da "meia-noite" retornavam antes de
     * `consumirEmPonto`, então o qualificador vazava: "meio-dia em ponto" virava a tarefa **"Ponto"**,
     * com a hora 12:00 certa e um título que ela não reconhece. Medido na `main` antes do fix.
     */
    @Test
    fun emPontoNaoSobraNoTituloDoMeioDiaNemDaMeiaNoite() {
        val meioDia = parser.parse("meio-dia em ponto")
        assertThat(meioDia.localTime).isEqualTo(LocalTime.of(12, 0))
        assertThat(meioDia.title).isEmpty()

        val meiaNoite = parser.parse("meia-noite em ponto")
        assertThat(meiaNoite.localTime).isEqualTo(LocalTime.of(0, 0))
        assertThat(meiaNoite.title).isEmpty()

        // Com tarefa junto, o qualificador sai e o nome fica.
        val comTarefa = parser.parse("tomar remédio meio-dia em ponto")
        assertThat(comTarefa.localTime).isEqualTo(LocalTime.of(12, 0))
        assertThat(comTarefa.title).isEqualTo("Tomar remédio")

        // A regra da casa não regride: sem hora reconhecida o "em ponto" não inventa uma.
        assertThat(parser.parse("amanhã três horas em ponto").localTime).isNull()
    }

    @Test
    fun feiraComoDiaNaoVoltaAoTituloComDeterminante() {
        // P1-A (3ª revisão): o guard do substantivo poupava o "feira" de QUALQUER "feira" seguido
        // de de/do/da/dos/das. Como o dia com ESPAÇO só consome o " feira" quando o próximo token
        // não é um desses, o sufixo do dia sobrava órfão e o guard o poupava: "sexta feira do
        // dentista" virava título "Feira dentista" — regressão contra o main ("Dentista").
        // A classe inteira fecha: o guard só poupa o "feira" quando ele ENCABEÇA o sintagma
        // nominal ("feira de ciências"), onde o determinante vem depois do próprio substantivo.
        val sexta = parser.parse("sexta feira do dentista às 8h")
        assertThat(sexta.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(sexta.title).isEqualTo("Dentista")

        assertThat(parser.parse("segunda feira do médico às 8h").title).isEqualTo("Médico")
        assertThat(parser.parse("terça feira da natação às 18h").title).isEqualTo("Natação")
        assertThat(parser.parse("quarta feira do curso às 19h").title).isEqualTo("Curso")
        assertThat(parser.parse("quinta feira da reunião às 9h").title).isEqualTo("Reunião")
        // O "feira" que é o mercado continua no título, com o dia nomeado depois.
        assertThat(parser.parse("ir na feira do bairro sábado às 10h").title.lowercase())
            .contains("feira")

        // O substantivo encabeçando o sintagma (P2-3) não pode regredir.
        assertThat(parser.parse("sexta feira de ciências às 8h").title.lowercase()).contains("feira")
        assertThat(parser.parse("sábado feira às 8h").title).isEqualTo("Feira")
        assertThat(parser.parse("sexta-feira do dentista às 8h").title).isEqualTo("Dentista")
        assertThat(parser.parse("na sexta feira dentista às 8h").title).isEqualTo("Dentista")
    }

    @Test
    fun diaDitoComRelativoNaoSeContradiz() {
        // P1-B (3ª revisão): o ramo do "daqui a N dias/semanas" devolvia a conta crua sem nunca
        // olhar o dia nomeado na mesma frase. Quinta 20/08, "sexta daqui a dois dias" caía no
        // SÁBADO 22/08 — e como não marcava ambíguo, a caixa rápida confirmava em silêncio um dia
        // que ela não disse. O dia nomeado é a expressão específica e manda — mas quando ele e a
        // conta crua DISCORDAM, o rascunho escala em vez de cravar um dos dois calado (P1 deste
        // lote): o "amb=true" é parte do contrato, não um detalhe.
        val sexta = parser.parse("sexta daqui a dois dias pagar conta às 10h")
        assertThat(sexta.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(sexta.title).isEqualTo("Pagar conta")
        assertThat(sexta.ambiguous).isTrue()

        assertThat(parser.parse("domingo daqui a dois dias pagar conta às 10h").localDate)
            .isEqualTo(LocalDate.of(2026, 8, 23))
        assertThat(parser.parse("segunda daqui a dois dias pagar conta às 10h").localDate)
            .isEqualTo(LocalDate.of(2026, 8, 24))

        // Com a semana o deslocamento cru cai em quinta: o dia dito ganha e é a PRIMEIRA sexta
        // depois de hoje, sem pular uma semana inteira — mas ambíguo, porque as duas datas ditas
        // não caem juntas.
        assertThat(parser.parse("sexta daqui a uma semana pagar conta às 10h").localDate)
            .isEqualTo(LocalDate.of(2026, 8, 21))
        val duasSemanas = parser.parse("sexta daqui a duas semanas pagar conta às 10h")
        assertThat(duasSemanas.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(duasSemanas.ambiguous).isTrue()

        // Quando o dia dito e a conta concordam, nada muda e a caixa rápida continua confirmando.
        val sabado = parser.parse("sábado daqui a dois dias pagar conta às 10h")
        assertThat(sabado.localDate).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(sabado.ambiguous).isFalse()
        assertThat(sabado.canQuickConfirm(clock.instant(), zone)).isTrue()

        // Sem dia nomeado o relativo continua valendo sozinho.
        assertThat(parser.parse("daqui a dois dias pagar conta às 10h").localDate)
            .isEqualTo(LocalDate.of(2026, 8, 22))
    }

    // ---- A1: data numérica sem ano que já passou não rola para o ano seguinte calada ----

    @Test
    fun dataNumericaSemAnoJaPassadaNaoRolaOAnoSeguinteEmSilencio() {
        // Hoje é quinta, 20/08/2026. O 05/08 deste ano era cinco dias atrás; o parser rolava para
        // 2027-08-05 com ambiguous=false e a caixa rápida confirmava quase um ano à frente calada.
        val reuniao = parser.parse("reunião 05/08 às 10h")
        assertThat(reuniao.localDate).isEqualTo(LocalDate.of(2027, 8, 5))
        assertThat(reuniao.ambiguous).isTrue()
        assertThat(reuniao.notes.joinToString()).contains("já passou")
        assertThat(reuniao.canQuickConfirm(clock.instant(), zone)).isFalse()

        val consulta = parser.parse("consulta 12/08 às 10h")
        assertThat(consulta.localDate).isEqualTo(LocalDate.of(2027, 8, 12))
        assertThat(consulta.ambiguous).isTrue()
    }

    @Test
    fun dataNumericaSemAnoAindaFuturaContinuaCertaESemAmbiguidade() {
        // O contraste que não pode regredir: o Natal deste ano ainda não chegou.
        val natal = parser.parse("prova 25/12 às 09:30")
        assertThat(natal.localDate).isEqualTo(LocalDate.of(2026, 12, 25))
        assertThat(natal.ambiguous).isFalse()

        val futuro = parser.parse("médico 22/08 às 10h")
        assertThat(futuro.localDate).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(futuro.ambiguous).isFalse()
    }

    // ---- A2: data que não existe é recusada, nunca clampada para o último dia do mês ----

    @Test
    fun dataImpossivelNaoViraOUltimoDiaDoMes() {
        // O `clampToValidDate` devolvia 28/02 para 31/02 em silêncio, com a caixa rápida confirmando.
        listOf("31/02", "30/02", "31/04", "31/06").forEach { raw ->
            val draft = parser.parse("reunião $raw às 10h")
            assertThat(draft.localDate).isNull()
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.missingFields).contains(MissingDraftField.DATE)
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    @Test
    fun vinteENoveDeFevereiroValeNoBissextoENaoVira28() {
        // Data válida em ano bissexto: não pode ser rejeitada.
        val bissexto = parser.parse("consulta 29/02/2028 às 10h")
        assertThat(bissexto.localDate).isEqualTo(LocalDate.of(2028, 2, 29))
        assertThat(bissexto.ambiguous).isFalse()

        // Sem ano, o próximo 29 de fevereiro de verdade é o de 2028 — e o rascunho fica ambíguo,
        // porque o ano é palpite.
        val semAno = parser.parse("consulta 29/02 às 10h")
        assertThat(semAno.localDate).isEqualTo(LocalDate.of(2028, 2, 29))
        assertThat(semAno.ambiguous).isTrue()

        // Em ano não bissexto, 29/02 não é 28/02: a data não existe e o app recusa.
        val naoBissexto = parser.parse("consulta 29/02/2027 às 10h")
        assertThat(naoBissexto.localDate).isNull()
        assertThat(naoBissexto.ambiguous).isTrue()

        // Com o ano dito, a data impossível também é recusa — o caminho que clampava calado.
        val comAno = parser.parse("reunião 31/02/2027 às 10h")
        assertThat(comAno.localDate).isNull()
        assertThat(comAno.ambiguous).isTrue()
    }

    @Test
    fun dataPorExtensoImpossivelTambemERecusada() {
        val impossivel = parser.parse("reunião 31 de fevereiro às 10h")
        assertThat(impossivel.localDate).isNull()
        assertThat(impossivel.ambiguous).isTrue()

        // O 29 de fevereiro por extenso, sem ano, rola para o próximo bissexto em vez de virar 28.
        val bissexto = parser.parse("reunião 29 de fevereiro às 10h")
        assertThat(bissexto.localDate).isEqualTo(LocalDate.of(2028, 2, 29))
    }

    // ---- A3: a refeição é o núcleo da tarefa, não um filler ----

    @Test
    fun refeicaoEhONucleoDoTituloNaoUmFiller() {
        // Hoje é quinta, 20/08/2026, 15:00 — o relógio do achado.
        val tarde = LocalDateTime.of(2026, 8, 20, 15, 0)
        val p = parserEm(tarde)

        val domingo = p.parse("almoço de domingo às 12h")
        assertThat(domingo.title).isEqualTo("Almoço")
        assertThat(domingo.missingFields).doesNotContain(MissingDraftField.TITLE)
        assertThat(domingo.canQuickConfirm(tarde.atZone(zone).toInstant(), zone)).isTrue()

        val invertido = p.parse("almoço às 12h de domingo")
        assertThat(invertido.title).isEqualTo("Almoço")
        assertThat(invertido.missingFields).doesNotContain(MissingDraftField.TITLE)

        val comMeninas = p.parse("almoço com as meninas sábado às 12h")
        assertThat(comMeninas.title).isEqualTo("Almoço com meninas")

        val naCasaDaFilha = p.parse("almoço na casa da filha domingo às 12h")
        assertThat(naCasaDaFilha.title).isEqualTo("Almoço casa filha")
    }

    @Test
    fun refeicaoComoDataDoAlmocoContinuaSemInventarHora() {
        // O "depois do almoço" continua sendo período vago: não virou título nem hora.
        val draft = parser.parse("sexta-feira depois do almoço")
        assertThat(draft.localDate!!.dayOfWeek).isEqualTo(DayOfWeek.FRIDAY)
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
    }

    // ---- A4: "dia N de <mês>" não deixa o "dia" no título ----

    @Test
    fun naoRegrideNasFrasesMedidasDoAchado() {
        // As frases que o lote de datas numéricas poderia quebrar de tabela.
        val oitoDaNoite = parser.parse("tomar remédio às 8 da noite")
        assertThat(oitoDaNoite.localTime).isEqualTo(LocalTime.of(20, 0))

        val amanhaDeManha = parser.parse("tomar remédio amanhã de manhã")
        assertThat(amanhaDeManha.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(amanhaDeManha.localTime).isNull()
        assertThat(amanhaDeManha.ambiguous).isTrue()

        val deDuasEmDuas = parser.parse("remédio de duas em duas horas")
        assertThat(deDuasEmDuas.localTime).isNull()
        assertThat(deDuasEmDuas.ambiguous).isTrue()
        assertThat(deDuasEmDuas.title).isEqualTo("Remédio")
    }

    @Test
    fun diaNumeroDeMesNaoDeixaODiaNoTitulo() {
        val missa = parser.parse("dia 15 de novembro missa às 10h")
        assertThat(missa.localDate).isEqualTo(LocalDate.of(2026, 11, 15))
        assertThat(missa.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(missa.title).isEqualTo("Missa")

        val desfile = parser.parse("dia 7 de setembro desfile")
        assertThat(desfile.localDate).isEqualTo(LocalDate.of(2026, 9, 7))
        assertThat(desfile.title).isEqualTo("Desfile")

        val comNo = parser.parse("no dia 15 de novembro missa às 10h")
        assertThat(comNo.localDate).isEqualTo(LocalDate.of(2026, 11, 15))
        assertThat(comNo.title).isEqualTo("Missa")
    }

    /**
     * A outra metade do A4: o "dia"/"no dia" antes do dia **por extenso**. Só o caminho de dígito
     * tinha teste, então tirar o `(?:no\s+)?dia\s+` do `extensoPalavra` deixava a suíte verde
     * enquanto "no dia quinze de novembro missa" voltava a virar "Dia missa".
     */
    @Test
    fun diaPorExtensoComOPrefixoNaoDeixaODiaNoTitulo() {
        val comNo = parser.parse("no dia quinze de novembro missa às 10h")
        assertThat(comNo.localDate).isEqualTo(LocalDate.of(2026, 11, 15))
        assertThat(comNo.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(comNo.title).isEqualTo("Missa")

        val semNo = parser.parse("dia quinze de novembro missa às 10h")
        assertThat(semNo.localDate).isEqualTo(LocalDate.of(2026, 11, 15))
        assertThat(semNo.title).isEqualTo("Missa")
    }

    // ---- F1: o dia do mês avulso ("no dia 31") também recusa a data que não existe ----

    @Test
    fun diaDoMesAvulsoQueNaoExisteERecusado() {
        // Hoje é 10/02/2026. O caminho do dia avulso não passa pelo `resolveDate` e continuava
        // clampando: "no dia 31" virava 28/02 com `ambiguous = false` e a caixa rápida confirmava.
        val fevereiro = parserEm(LocalDateTime.of(2026, 2, 10, 15, 0))
        val agora = LocalDateTime.of(2026, 2, 10, 15, 0).atZone(zone).toInstant()
        listOf(
            "consulta no dia 31 às 10h",
            "consulta dia 31 às 10h",
            "consulta no dia 30 às 10h",
        ).forEach { frase ->
            val draft = fevereiro.parse(frase)
            assertThat(draft.localDate).isNull()
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.missingFields).contains(MissingDraftField.DATE)
            assertThat(draft.canQuickConfirm(agora, zone)).isFalse()
        }

        // O dia que existe no mês continua resolvido e sem ambiguidade.
        val valido = fevereiro.parse("consulta no dia 25 às 10h")
        assertThat(valido.localDate).isEqualTo(LocalDate.of(2026, 2, 25))
        assertThat(valido.ambiguous).isFalse()

        // O 29 em fevereiro de um ano comum não existe — e não pode virar 28/02 nem estourar.
        val vinteENove = fevereiro.parse("consulta no dia 29 às 10h")
        assertThat(vinteENove.localDate).isNull()
        assertThat(vinteENove.ambiguous).isTrue()

        // E o dia 31 de um mês que tem 31 continua valendo.
        val trintaEUm = fevereiro.parse("consulta no dia 31 de março às 10h")
        assertThat(trintaEUm.localDate).isEqualTo(LocalDate.of(2026, 3, 31))
        assertThat(trintaEUm.ambiguous).isFalse()
    }

    // ---- F2: a série anual com dia impossível no mês dito também é recusa ----

    @Test
    fun serieComDiaImpossivelNoMesFicaAmbigua() {
        // O ramo `yearlyExtenso` ("todo dia N de <mês>", a forma como ela fala) devolvia
        // `ambiguous = false` literal, sem o guard que os outros ramos do mesmo `extractRecurrence`
        // têm: a caixa rápida confirmava calada uma série cujo dia não existe.
        val fevereiro = parserEm(LocalDateTime.of(2026, 2, 10, 15, 0))
        val agora = LocalDateTime.of(2026, 2, 10, 15, 0).atZone(zone).toInstant()
        listOf(
            "todo dia 32 de fevereiro remédio às 10h",
            "todo dia 99 de fevereiro remédio às 10h",
            "todo dia 31 de abril remédio às 10h",
        ).forEach { frase ->
            val draft = fevereiro.parse(frase)
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.canQuickConfirm(agora, zone)).isFalse()
        }

        // A mesma data dita com "todo ano" já era recusada por aquele ramo — o guard que faltava
        // aqui era o do `yearlyExtenso`, que é a forma como ela fala ("todo dia N de <mês>").
        assertThat(fevereiro.parse("todo ano dia 32 de fevereiro remédio às 10h").ambiguous).isTrue()
        assertThat(fevereiro.parse("todo ano dia 31 de abril remédio às 10h").ambiguous).isTrue()

        // O dia 31 existe em meses de 31 dias: a série anual continua certa e sem ambiguidade.
        assertThat(fevereiro.parse("todo dia 31 de maio remédio às 10h").ambiguous).isFalse()

        // A série possível continua certa, inclusive o 29 de fevereiro (a série cai no bissexto).
        assertThat(fevereiro.parse("todo dia 15 de maio remédio às 10h").ambiguous).isFalse()
        assertThat(fevereiro.parse("todo dia 29 de fevereiro remédio às 10h").ambiguous).isFalse()
    }

    // ---- Regressões do lote do #68 (revisão independente de 06/10/2026) ----

    @Test
    fun feiraComAdverbioDeTempoNaoEhPoupadaComoSufixo() {
        // O guard do "feira" (FEIRA_NOUN) tratava todo "de" como complemento nominal, mas
        // "de manhã"/"de tarde"/"de noite" é ADVÉRBIO de tempo: ali o "feira" é o sufixo do dia,
        // e sobrava no título ("Feira dentista") com a caixa rápida confirmando.
        listOf(
            "sexta feira de manhã dentista às 8h",
            "na segunda feira de tarde dentista às 8h",
            "quarta feira de noite dentista às 8h",
        ).forEach { frase ->
            assertThat(parser.parse(frase).title).isEqualTo("Dentista")
        }

        // O hífen já funcionava e não regride.
        assertThat(parser.parse("sexta-feira de manhã dentista às 8h").title).isEqualTo("Dentista")

        // O SUBSTANTIVO continua poupado: "feira de <complemento>" e "na feira do bairro".
        assertThat(parser.parse("sexta feira de ciências às 8h").title.lowercase()).contains("feira")
        assertThat(parser.parse("ir na feira do bairro sábado às 8h").title.lowercase()).contains("feira")
    }

    @Test
    fun feiraComAdverbioDeTempoCobreAsVariantesDoPeriodo() {
        // F3 da revisão de 06/10: o guard do "feira" declarou fechada a classe
        // "<dia> feira de <advérbio>", mas `TIME_ADVERB_SRC` só tinha as quatro palavras exatas e o
        // `\b` cortava as variantes: "de manhãzinha", "de noitinha", "de tardezinha" e "de dia"
        // devolviam o "Feira" ao título, não-ambíguas e confirmáveis na caixa rápida. O `main` não
        // tem esse "Feira" — o prefixo do advérbio fecha a classe de verdade.
        listOf(
            "sexta feira de manhãzinha dentista às 8h",
            "sexta feira de noitinha dentista às 8h",
            "sexta feira de tardezinha dentista às 8h",
            "sexta feira de dia dentista às 8h",
        ).forEach { frase ->
            val d = parser.parse(frase)
            assertThat(d.title.lowercase()).doesNotContain("feira")
            assertThat(d.title.lowercase()).contains("dentista")
            assertThat(d.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        }

        // O substantivo continua poupado: "feira de <complemento>".
        assertThat(parser.parse("sexta feira de ciências às 8h").title.lowercase()).contains("feira")
    }

    @Test
    fun oraculoDiaDitoComRelativoNaoEscalaQuandoAsContasConcordam() {
        // F1 da revisão de 06/10, como oráculo de invariante (não exemplos soltos): cruza os 7 dias
        // × N=1..7 × {dias, semanas} = 98 casos e conta as violações. A regra:
        //   - conta crua caindo NO dia dito -> as duas expressões concordam, existe UMA data (a conta
        //     crua) e o rascunho NÃO escala;
        //   - caso contrário -> vale a primeira ocorrência do dia dito e o rascunho ESCALA.
        // O predicado `onNamedDay != date` tratava o offset de semana inteira sobre o dia da semana
        // de hoje (onde os dois concordam) como discordância e devolvia HOJE.
        val nomes = mapOf(
            DayOfWeek.MONDAY to "segunda",
            DayOfWeek.TUESDAY to "terça",
            DayOfWeek.WEDNESDAY to "quarta",
            DayOfWeek.THURSDAY to "quinta",
            DayOfWeek.FRIDAY to "sexta",
            DayOfWeek.SATURDAY to "sábado",
            DayOfWeek.SUNDAY to "domingo",
        )
        val hoje = clock.today()
        var violacoes = 0
        val amostra = mutableListOf<String>()
        for ((dia, nome) in nomes) {
            for (n in 1..7) {
                for (unidade in listOf("dias", "semanas")) {
                    val frase = "$nome daqui a $n $unidade pagar conta às 10h"
                    val crua = if (unidade == "semanas") hoje.plusWeeks(n.toLong()) else hoje.plusDays(n.toLong())
                    val esperadoData: LocalDate
                    val esperadoAmbiguo: Boolean
                    if (crua.dayOfWeek == dia) {
                        esperadoData = crua
                        esperadoAmbiguo = false
                    } else {
                        esperadoData = primeiraOcorrenciaDe(dia, hoje)
                        esperadoAmbiguo = true
                    }
                    val d = parser.parse(frase)
                    if (d.localDate != esperadoData || d.ambiguous != esperadoAmbiguo) {
                        violacoes++
                        if (amostra.size < 12) {
                            amostra += "$frase -> ${d.localDate}/amb=${d.ambiguous} (esperado $esperadoData/amb=$esperadoAmbiguo)"
                        }
                    }
                }
            }
        }
        println("ORACULO_F1 violacoes=$violacoes de ${nomes.size * 7 * 2}")
        amostra.forEach { println("ORACULO_F1 $it") }
        assertThat(violacoes).isEqualTo(0)
    }

    @Test
    fun oraculoDoisDiasDitosComRelativoEscalam() {
        // F2 da revisão de 06/10: pares ordenados de dias (7×6=42) × N=1..7 × {dias, semanas} = 588
        // casos. Com DOIS dias ditos e um deslocamento, o guard do delta só olhava `size == 1` e o
        // ramo caía em "conta crua, não-ambíguo" — a caixa rápida confirmava uma quinta para uma
        // frase que diz sábado e domingo. O `main` escalava todos os discordantes; o delta regrediu.
        // Regra: se a conta crua não cai em NENHUM dos dias ditos, nenhum foi honrado -> escala.
        val nomes = mapOf(
            DayOfWeek.MONDAY to "segunda",
            DayOfWeek.TUESDAY to "terça",
            DayOfWeek.WEDNESDAY to "quarta",
            DayOfWeek.THURSDAY to "quinta",
            DayOfWeek.FRIDAY to "sexta",
            DayOfWeek.SATURDAY to "sábado",
            DayOfWeek.SUNDAY to "domingo",
        )
        val hoje = clock.today()
        var violacoes = 0
        var casos = 0
        val amostra = mutableListOf<String>()
        for ((diaA, nomeA) in nomes) {
            for ((diaB, nomeB) in nomes) {
                if (diaA == diaB) continue
                for (n in 1..7) {
                    for (unidade in listOf("dias", "semanas")) {
                        casos++
                        val frase = "$nomeA e $nomeB daqui a $n $unidade pagar conta às 10h"
                        val crua = if (unidade == "semanas") hoje.plusWeeks(n.toLong()) else hoje.plusDays(n.toLong())
                        val esperadoAmbiguo = crua.dayOfWeek != diaA && crua.dayOfWeek != diaB
                        val d = parser.parse(frase)
                        if (d.ambiguous != esperadoAmbiguo) {
                            violacoes++
                            if (amostra.size < 12) {
                                amostra += "$frase -> ${d.localDate}/amb=${d.ambiguous} (esperado amb=$esperadoAmbiguo)"
                            }
                        }
                    }
                }
            }
        }
        println("ORACULO_F2 violacoes=$violacoes de $casos")
        amostra.forEach { println("ORACULO_F2 $it") }
        assertThat(violacoes).isEqualTo(0)

        // O exemplo medido: sábado e domingo + duas semanas caem na QUINTA 03/09, nenhum dos dois.
        val medido = parser.parse("sábado e domingo daqui a duas semanas pagar conta às 10h")
        assertThat(medido.localDate).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(medido.ambiguous).isTrue()
        assertThat(medido.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    private fun primeiraOcorrenciaDe(dia: DayOfWeek, de: LocalDate): LocalDate {
        var cursor = de
        while (cursor.dayOfWeek != dia) cursor = cursor.plusDays(1)
        return cursor
    }

    @Test
    fun diaDitoComRelativoDivergenteEscala() {
        // P1 deste lote: o dia nomeado vencia SEMPRE e descartava o N em silêncio — "quinta daqui a
        // duas semanas" caía em HOJE (20/08), completa e não-ambígua. Quando o dia dito e o
        // deslocamento cru discordam, o dia dito continua (é a expressão específica), mas o
        // rascunho escala — cravar um dos dois calado é o defeito.
        // A conta crua CAI no dia dito (hoje + 2 semanas é uma quinta): as duas expressões
        // concordam, a data é 03/09 e não há dúvida a escalar. O teste antigo prendia 20/08 (HOJE)
        // — o próprio exemplo que o PR usava para descrever o defeito que dizia ter consertado.
        val quintaDuasSemanas = parser.parse("quinta daqui a duas semanas pagar conta às 10h")
        assertThat(quintaDuasSemanas.localDate).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(quintaDuasSemanas.ambiguous).isFalse()
        assertThat(quintaDuasSemanas.canQuickConfirm(clock.instant(), zone)).isTrue()

        val quintaSeteSemanas = parser.parse("quinta daqui a 7 semanas pagar conta às 10h")
        assertThat(quintaSeteSemanas.localDate).isEqualTo(LocalDate.of(2026, 10, 8))
        assertThat(quintaSeteSemanas.ambiguous).isFalse()

        val sextaDuasSemanas = parser.parse("sexta daqui a duas semanas pagar conta às 10h")
        assertThat(sextaDuasSemanas.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(sextaDuasSemanas.ambiguous).isTrue()
    }
}

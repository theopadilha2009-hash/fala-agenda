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
}

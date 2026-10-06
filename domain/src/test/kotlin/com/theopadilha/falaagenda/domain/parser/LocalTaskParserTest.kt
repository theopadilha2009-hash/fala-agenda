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
        assertThat(antes.localTime).isEqualTo(LocalTime.of(20, 0))
    }

    @Test
    fun antesDoJantarComHoraDaTardeNaoViraMadrugada() {
        val draft = parser.parse("amanhã antes do jantar às seis")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(18, 0))
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
    fun horaFaladaSemAsComPeriodo() {
        // O reconhecedor de fala derruba o "às": sem ele a hora ficava nula e a palavra sobrava no
        // título ("Tomar remédio duas"), escalando para a IA sem necessidade.
        val duas = parser.parse("tomar remédio duas da tarde")
        assertThat(duas.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(duas.title).isEqualTo("Tomar remédio")
        assertThat(duas.ambiguous).isFalse()

        val oito = parser.parse("tomar remédio 8 da manhã")
        assertThat(oito.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(oito.title).isEqualTo("Tomar remédio")

        val tres = parser.parse("tomar remédio três da tarde")
        assertThat(tres.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(tres.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun horaFaladaSemAsContinuaSomandoOPeriodo() {
        // "oito da noite" sem o "às" também soma 12 (20h), como com o "às".
        val noite = parser.parse("tomar remédio oito da noite")
        assertThat(noite.localTime).isEqualTo(LocalTime.of(20, 0))

        // Contraste que já funcionava: com o "às" não pode regredir.
        val comAs = parser.parse("tomar remédio às 8 da noite")
        assertThat(comAs.localTime).isEqualTo(LocalTime.of(20, 0))
    }

    @Test
    fun emPontoNaoViraHoraNemSobraNoTitulo() {
        // "amanhã três horas em ponto" ficava sem hora e com título "Três ponto"; com o "às" a hora
        // saía certa mas o título virava "Ponto tomar remédio".
        val tresHoras = parser.parse("amanhã três horas em ponto")
        assertThat(tresHoras.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(tresHoras.title).doesNotContain("Ponto")

        val oito = parser.parse("tomar remédio oito em ponto")
        assertThat(oito.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(oito.title).isEqualTo("Tomar remédio")

        val comAs = parser.parse("às três em ponto tomar remédio")
        assertThat(comAs.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(comAs.title).isEqualTo("Tomar remédio")
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
    fun f1EmPontoLeOPeriodoEOsMinutos() {
        // A regex do "em ponto" tem 4 grupos: hora, minutos em dígito, minutos por extenso
        // (o "e meia") e período. O código lia o 3 (o "e meia") como se fosse período e nunca
        // lia o 4 — o "da tarde" era descartado. Em main, "três em ponto da tarde" era 15:00.
        val tarde = parser.parse("amanhã às três em ponto da tarde tomar remédio")
        assertThat(tarde.localTime).isEqualTo(LocalTime.of(15, 0))
        assertThat(tarde.ambiguous).isFalse()

        val noite = parser.parse("amanhã oito em ponto da noite tomar remédio")
        assertThat(noite.localTime).isEqualTo(LocalTime.of(20, 0))

        val meia = parser.parse("amanhã oito e meia em ponto tomar remédio")
        assertThat(meia.localTime).isEqualTo(LocalTime.of(8, 30))

        // Os dois grupos juntos: o "e meia" (minuto) e o período, na mesma frase.
        val juntos = parser.parse("amanhã oito e meia em ponto da noite tomar remédio")
        assertThat(juntos.localTime).isEqualTo(LocalTime.of(20, 30))

        // "três e meia em ponto" sem período: 3h da manhã ou da tarde. Em main era 03:30 ambíguo.
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

        // O contraste que já funcionava: a palavra intermediária salva ("duas gotas").
        val gotas = parser.parse("tomar duas gotas de manhã")
        assertThat(gotas.localTime).isNull()
        assertThat(gotas.ambiguous).isTrue()

        // O caso que a auditoria quer: "duas da tarde" (com artigo) é 14:00, sem escalar.
        val daTarde = parser.parse("tomar remédio duas da tarde")
        assertThat(daTarde.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(daTarde.ambiguous).isFalse()
        assertThat(daTarde.title).isEqualTo("Tomar remédio")
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
        // hora nem verbo na segunda oração de dose.
        val draft = parser.parse("amanhã tomar duas da tarde e três da noite")
        assertThat(draft.ambiguous).isTrue()
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
}

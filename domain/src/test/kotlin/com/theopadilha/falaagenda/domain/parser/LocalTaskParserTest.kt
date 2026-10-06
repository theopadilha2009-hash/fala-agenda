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

    @Test
    fun emPontoEhQualificadorDaHoraNaoDoTitulo() {
        // B: "em ponto" vazava para o título ("Ponto") e, sem o "às", a hora se perdia.
        val oitoEMeia = parser.parse("oito e meia em ponto")
        assertThat(oitoEMeia.localTime).isEqualTo(LocalTime.of(8, 30))
        assertThat(oitoEMeia.title).isEmpty()

        val meioDia = parser.parse("meio-dia em ponto")
        assertThat(meioDia.localTime).isEqualTo(LocalTime.of(12, 0))
        assertThat(meioDia.title).isEmpty()

        val tres = parser.parse("três em ponto")
        assertThat(tres.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(tres.title).isEmpty()

        // "às três em ponto" continua 03:00 (ambíguo de manhã/tarde), sem "Ponto" no título.
        val asTres = parser.parse("às três em ponto")
        assertThat(asTres.localTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(asTres.title).isEmpty()
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
}

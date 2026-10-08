package com.theopadilha.falaagenda.domain.parser
 import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
 /**
 * O dia da semana não pode apagar o valor, a hora por extenso nem a conjunção do resto da frase. *
 * `stripWeekDays` rodava `replace(Regex("""\b(e|,)\b"""), " ")` sobre TODO o texto depois do dia,
 * sem saber o papel do token. Como `extractRecurrence` roda ANTES de `extractAmount` e de
 * `extractTime`, a limpeza comia a vírgula DECIMAL ("30,50" virava "30 50" → R$50,00) e o "e" do
 * número ou do minuto ("oito e meia" virava "oito meia" → sem hora; "às sete e meia" virava
 * 07:00). A fala com o dia ANTES do valor é a que denuncia: com o valor antes do dia, o
 * `extractAmount` já o tinha consumido e a limpeza não o alcançava. *
 * O invariante que fecha a classe: a MESMA fala, com e sem o dia da semana, produz o mesmo título,
 * o mesmo valor e a mesma hora. A conjunção e a vírgula do título não dependem desta limpeza —
 * o `e` já é `FILLER` em `extractTitle` e a vírgula solta já sai no `.trim(...)` —, então o que
 * sobra para o `stripWeekDays` é remover SÓ os dias. */
class LocalTaskParserDiaDaSemanaComValorTest {
 private val zone = ZoneId.of("America/Sao_Paulo")
 private val parser = LocalTaskParser(
 FixedAppClock(LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(), zone),
 )
 // ---------------------------------------------------------------------------------------------
 // P0 — o valor em reais sobrevive ao dia da semana. // ---------------------------------------------------------------------------------------------
 @Test
 fun valorDecimalComDiaDaSemanaAntesDoValor() {
 val draft = parser.parse("toda segunda pagar 30,50 reais")
 assertThat(draft.amountCents).isEqualTo(3050)
 assertThat(draft.title).isEqualTo("Pagar")
 assertThat(draft.ambiguous).isFalse()
 }
 @Test
 fun valorDecimalComODiaDepoisDoValorContinuaIgual() {
 val draft = parser.parse("pagar 30,50 reais toda segunda")
 assertThat(draft.amountCents).isEqualTo(3050)
 assertThat(draft.title).isEqualTo("Pagar")
 assertThat(draft.recurrence.weekDays).containsExactly(DayOfWeek.MONDAY)
 }
 @Test
 fun valorMistoComDiaDaSemana() {
 val draft = parser.parse("toda segunda pagar 2 mil e 500 reais")
 assertThat(draft.amountCents).isEqualTo(250_000)
 assertThat(draft.title).isEqualTo("Pagar")
 assertThat(draft.ambiguous).isFalse()
 }
 @Test
 fun centavosComDiaDaSemana() {
 assertThat(parser.parse("toda segunda pagar 30 reais e 50 centavos").amountCents).isEqualTo(3050)
 assertThat(parser.parse("toda terça pagar 40 reais e 5 centavos").amountCents).isEqualTo(4005)
 assertThat(parser.parse("toda segunda pagar 15 reais e 20 centavos").amountCents).isEqualTo(1520)
 }
 @Test
 fun valorComMilharComDiaDaSemana() {
 val draft = parser.parse("toda segunda pagar 1.234,56 reais")
 assertThat(draft.amountCents).isEqualTo(123_456)
 assertThat(draft.title).isEqualTo("Pagar")
 }
 // ---------------------------------------------------------------------------------------------
 // P0 — a hora por extenso sobrevive ao dia da semana. // ---------------------------------------------------------------------------------------------
 @Test
 fun horaPorExtensoComDiaDaSemanaAntesDaHora() {
 assertThat(parser.parse("toda segunda tomar remédio oito e meia").localTime)
 .isEqualTo(LocalTime.of(8, 30))
 assertThat(parser.parse("toda segunda tomar remédio nove e vinte").localTime)
 .isEqualTo(LocalTime.of(9, 20))
 assertThat(parser.parse("toda segunda às dez e meia tomar remédio").localTime)
 .isEqualTo(LocalTime.of(10, 30))
 assertThat(parser.parse("toda segunda às dez e vinte tomar remédio").localTime)
 .isEqualTo(LocalTime.of(10, 20))
 }
 @Test
 fun horaComAsEComDiaDaSemana() {
 val draft = parser.parse("toda quarta tomar remédio às sete e meia")
 assertThat(draft.localTime).isEqualTo(LocalTime.of(7, 30))
 assertThat(draft.title).isEqualTo("Tomar remédio")
 assertThat(draft.missingFields).isEmpty()
 }
 // ---------------------------------------------------------------------------------------------
 // O invariante: com e sem o dia da semana, o desfecho é o mesmo. // ---------------------------------------------------------------------------------------------
 @Test
 fun mesmaFalaComEDiaDaSemanaDaOMesmoTituloValorEHora() {
 val pares = listOf(
 "toda segunda pagar 30,50 reais" to "pagar 30,50 reais",
 "pagar 30,50 reais toda segunda" to "pagar 30,50 reais",
 "toda segunda pagar 12,90 reais" to "pagar 12,90 reais",
 "toda segunda pagar 2 mil e 500 reais" to "pagar 2 mil e 500 reais",
 "toda segunda pagar 2 mil e quinhentos reais" to "pagar 2 mil e quinhentos reais",
 "toda segunda pagar 1.234,56 reais" to "pagar 1.234,56 reais",
 "toda segunda pagar 30 reais e 50 centavos" to "pagar 30 reais e 50 centavos",
 "toda segunda pagar trinta reais e cinquenta centavos" to "pagar trinta reais e cinquenta centavos",
 "toda segunda pagar 15 reais e 20 centavos" to "pagar 15 reais e 20 centavos",
 "toda terça pagar 40 reais e 5 centavos" to "pagar 40 reais e 5 centavos",
 "toda segunda tomar remédio oito e meia" to "tomar remédio oito e meia",
 "toda quarta tomar remédio às sete e meia" to "tomar remédio às sete e meia",
 "toda segunda tomar remédio nove e vinte" to "tomar remédio nove e vinte",
 "toda segunda às dez e meia tomar remédio" to "às dez e meia tomar remédio",
 "toda segunda às dez e vinte tomar remédio" to "às dez e vinte tomar remédio",
 "toda quinta reunião às nove e meia" to "reunião às nove e meia",
 "comprar pão e leite toda segunda" to "comprar pão e leite",
 "comprar arroz e feijão toda segunda" to "comprar arroz e feijão",
 "comprar dois quilos de tomate e um de cebola toda terça" to "comprar dois quilos de tomate e um de cebola",
 "toda segunda comprar pão, leite e ovos" to "comprar pão, leite e ovos",
 "toda segunda comprar leite e pão" to "comprar leite e pão",
 "toda sexta ir na feira" to "ir na feira",
 "ir na feira toda sexta" to "ir na feira",
 "almoçar com a filha toda quinta" to "almoçar com a filha",
 )
 pares.forEach { (comDia, semDia) ->
 val a = parser.parse(comDia)
 val b = parser.parse(semDia)
 assertThat(a.title).isEqualTo(b.title)
 assertThat(a.amountCents).isEqualTo(b.amountCents)
 assertThat(a.localTime).isEqualTo(b.localTime)
 }
 }
 // ---------------------------------------------------------------------------------------------
 // Controles: as mesmas falas sem o dia da semana, com os números presos. // ---------------------------------------------------------------------------------------------
 @Test
 fun controlesSemDiaDaSemanaNaoMudam() {
 val esperado = listOf(
 Triple("pagar 30,50 reais", "Pagar", 3050L),
 Triple("pagar 12,90 reais", "Pagar", 1290L),
 Triple("pagar 2 mil e 500 reais", "Pagar", 250_000L),
 Triple("pagar 2 mil e quinhentos reais", "Pagar", 250_000L),
 Triple("pagar 1.234,56 reais", "Pagar", 123_456L),
 Triple("pagar 30 reais e 50 centavos", "Pagar", 3050L),
 Triple("pagar trinta reais e cinquenta centavos", "Pagar", 3050L),
 Triple("pagar 15 reais e 20 centavos", "Pagar", 1520L),
 Triple("pagar 40 reais e 5 centavos", "Pagar", 4005L),
 )
 esperado.forEach { (fala, titulo, valor) ->
 val draft = parser.parse(fala)
 assertThat(draft.title).isEqualTo(titulo)
 assertThat(draft.amountCents).isEqualTo(valor)
 }
 assertThat(parser.parse("tomar remédio oito e meia").localTime).isEqualTo(LocalTime.of(8, 30))
 assertThat(parser.parse("tomar remédio nove e vinte").localTime).isEqualTo(LocalTime.of(9, 20))
 assertThat(parser.parse("às dez e vinte tomar remédio").localTime).isEqualTo(LocalTime.of(10, 20))
 assertThat(parser.parse("reunião às nove e meia").localTime).isEqualTo(LocalTime.of(9, 30))
 }
 // ---------------------------------------------------------------------------------------------
 // A série de dias e o título: o que a limpeza da conjunção protegia. // ---------------------------------------------------------------------------------------------
 @Test
 fun serieDeDoisDiasMantemOsDiasEOHorario() {
 val draft = parser.parse("toda segunda e quarta natação às 18h")
 assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
 assertThat(draft.recurrence.weekDays).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
 assertThat(draft.localTime).isEqualTo(LocalTime.of(18, 0))
 assertThat(draft.title).isEqualTo("Natação")
 assertThat(draft.ambiguous).isFalse()
 }
 @Test
 fun serieDeTresDiasSeparadosPorVirgula() {
 val draft = parser.parse("toda segunda, quarta e sexta natação")
 assertThat(draft.recurrence.weekDays)
 .containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
 assertThat(draft.title).isEqualTo("Natação")
 assertThat(draft.ambiguous).isFalse()
 }
 @Test
 fun serieSemTituloNaoInventaTexto() {
 val draft = parser.parse("toda segunda e quarta")
 assertThat(draft.title).isEmpty()
 assertThat(draft.recurrence.weekDays).containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
 assertThat(draft.missingFields).contains(MissingDraftField.TITLE)
 }
 // ---------------------------------------------------------------------------------------------
 // O dia dito uma vez, com data/hora resolvidas. // ---------------------------------------------------------------------------------------------
 @Test
 fun diaDaSemanaComDataEValor() {
 val draft = parser.parse("toda segunda pagar 30,50 reais")
 assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 24))
 assertThat(draft.recurrence.kind).isEqualTo(RecurrenceKind.WEEKLY)
 assertThat(draft.recurrence.weekDays).containsExactly(DayOfWeek.MONDAY)
 assertThat(draft.ambiguous).isFalse()
 }
 @Test
 fun falaAmbiguaSemDiaContinuaAmbigua() {
 val semDia: ParsedTaskDraft = parser.parse("natação segunda e quarta")
 assertThat(semDia.ambiguous).isTrue()
 val comDia = parser.parse("toda segunda tomar remédio às vinte e cinco para as nove")
 assertThat(comDia.ambiguous).isTrue()
 }
}

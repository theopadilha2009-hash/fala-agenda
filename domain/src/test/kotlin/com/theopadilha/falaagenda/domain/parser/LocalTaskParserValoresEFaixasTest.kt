package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Faixa de horário ("das 14 às 16h"), valor em reais, quantidade por dígito, "meia dúzia" e o
 * intervalo com a primeira dose dita. São as frases em que o app entendia errado e a caixa rápida
 * confirmava em silêncio — o alarme tocava na hora errada ou o título perdia o que ela falou.
 */
class LocalTaskParserValoresEFaixasTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    // ---- Faixa: o compromisso é no INÍCIO, nunca na hora de término ----

    @Test
    fun faixaDasQuatorzeAsDezesseisTocaNoInicio() {
        // Antes: o "14" não casava como hora (sem "h") e o "16h" virava a hora do alarme —
        // 2h depois do que ela disse, com a caixa rápida confirmando sozinha.
        val draft = parser.parse("fisioterapia das 14 às 16h amanhã")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(draft.title).isEqualTo("Fisioterapia")
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.missingFields).isEmpty()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
    }

    @Test
    fun faixaSemHNasDuasHorasTocaNoInicio() {
        val draft = parser.parse("trabalho das 8 às 17 amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Trabalho")
    }

    @Test
    fun faixaComPeriodoNoFimTocaNoInicio() {
        val draft = parser.parse("médico das 9 às 10 da manhã amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.title).isEqualTo("Médico")
    }

    @Test
    fun faixaPorExtensoComPeriodoNaoDeixaRestoNoTitulo() {
        // Antes: time=16:00 e title="Duas" — a hora de término e um fragmento do início.
        val draft = parser.parse("das duas às quatro da tarde amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(draft.title).doesNotContain("Duas")
    }

    @Test
    fun faixaPorExtensoSemPeriodoFicaAmbigua() {
        // "das duas às quatro" pode ser 2h ou 14h: mantém o palpite do início, mas marca ambíguo
        // para ela confirmar — a mesma regra de "às três" sem período. Cravar 14h seria inventar.
        val draft = parser.parse("reunião das duas às quatro amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(2, 0))
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.title).isEqualTo("Reunião")
    }

    @Test
    fun faixaComHNasDuasHorasTambemTocaNoInicio() {
        val draft = parser.parse("reunião das 14h às 16h amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(14, 0))
        assertThat(draft.title).isEqualTo("Reunião")
    }

    @Test
    fun faixaDeXASemHNaoCravaAHora() {
        // "de 14 a 16" não diz qual das duas é o compromisso: fica sem hora e escala, como hoje.
        // O que melhora é o título — antes o "14 16" sobrava nele.
        val draft = parser.parse("reunião de 14 a 16 amanhã")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isNull()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.title).isEqualTo("Reunião")
    }

    @Test
    fun entreNaoRegride() {
        // "entre 9 e 10" fica como estava: o alvo é a faixa com "das ... às ...".
        val draft = parser.parse("entre 9 e 10")
        assertThat(draft.title).isEqualTo("Entre")
    }

    @Test
    fun dasComHoraAvulsaContinuaVirandoHora() {
        // O "das 8" sozinho é a hora (o "das" abre a hora em pt-BR). Sem isto o "8" sobrava no
        // título depois que o filtro de dígitos saiu de `extractTitle`.
        val draft = parser.parse("reunião das 8 amanhã")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Reunião")
    }

    // ---- Valor em reais ----

    @Test
    fun valorEmDigitosComReais() {
        val draft = parser.parse("pagar a conta de luz de 120 reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(12000L)
        assertThat(draft.title).isEqualTo("Pagar conta luz")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 0))
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
    }

    @Test
    fun valorPorExtenso() {
        val draft = parser.parse("pagar a conta de luz de cento e vinte reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(12000L)
        assertThat(draft.title).isEqualTo("Pagar conta luz")
    }

    @Test
    fun valorComCifrao() {
        val draft = parser.parse("pagar R$ 30 amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(3000L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun valorPorExtensoComCentenaEDezema() {
        val draft = parser.parse("paguei duzentos e cinquenta reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(25000L)
        assertThat(draft.title).isEqualTo("Paguei")
    }

    @Test
    fun valorComMil() {
        val draft = parser.parse("pagar mil e duzentos reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(120000L)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun semValorNaoInventa() {
        val draft = parser.parse("comprar duas caixas de leite amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).isEqualTo("Comprar duas caixas leite")
    }

    // ---- Quantidade por dígito e "meia dúzia" ----

    @Test
    fun quantidadeEmDigitoFicaNoTitulo() {
        // Antes: "Comprar caixas leite" — ela não sabia quantas caixas eram.
        val caixas = parser.parse("comprar 2 caixas de leite amanhã às 10h")
        assertThat(caixas.title).isEqualTo("Comprar 2 caixas leite")

        val remedios = parser.parse("levar 3 remédios amanhã às 10h")
        assertThat(remedios.title).isEqualTo("Levar 3 remédios")

        val ovos = parser.parse("comprar 12 ovos amanhã às 10h")
        assertThat(ovos.title).isEqualTo("Comprar 12 ovos")
    }

    @Test
    fun meiaDuziaNaoViraDuzia() {
        // Antes: title="Comprar dúzia ovos" — o dobro do que ela pediu.
        val draft = parser.parse("comprar meia dúzia de ovos amanhã às 10h")
        assertThat(draft.title).isEqualTo("Comprar meia dúzia ovos")
    }

    // ---- Intervalo com a primeira dose dita ----

    @Test
    fun intervaloComPrimeiraDoseNaoJogaAHoraFora() {
        // Antes: time=null e a nota pedia exatamente o horário que ela acabou de falar.
        val draft = parser.parse("tomar remédio a cada 12 horas começando amanhã às 8h")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.missingFields).doesNotContain(MissingDraftField.TIME)
    }

    @Test
    fun intervaloSemPrimeiraDoseContinuaPedindo() {
        // Sem hora dita não há o que preencher: continua ambíguo e pedindo a primeira dose.
        val draft = parser.parse("remédio a cada duas horas")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.missingFields).contains(MissingDraftField.TIME)
        assertThat(draft.title).isEqualTo("Remédio")
        assertThat(draft.notes.joinToString()).contains("intervalo")
    }
}

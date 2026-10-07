package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * O valor em reais quando a fala dita a escala por EXTENSO e fecha o valor com a parcela em DÍGITO
 * ("dois mil e 500 reais", "mil e 500 reais") — o espelho exato do dígito+extenso corrigido no #91.
 *
 * O defeito é o mais caro do app: grava R$500,00 no lugar de R$2.500,00, com `qc=true` e sem nota,
 * e a caixa rápida confirma em um toque. O esperado de cada caso é aritmética escrita à mão —
 * nunca derivada da lógica do parser.
 */
class LocalTaskParserValorExtensoComParcelaEmDigitoTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    // ---- O defeito relatado: a escala por extenso e a parcela em dígito ----

    @Test
    fun extensoComParcelaEmDigitoSomaAsParcelas() {
        // "pagar dois mil e 500 reais" gravava R$500,00 e deixava o "dois mil" no título.
        val draft = parser.parse("pagar dois mil e 500 reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((2 * 1000 + 500) * 100).toLong())
        assertThat(draft.title).isEqualTo("Pagar")

        // "pagar mil e 500 reais" gravava R$500,00 com o "mil" no título.
        val mil = parser.parse("pagar mil e 500 reais amanhã às 10h")
        assertThat(mil.amountCents).isEqualTo(((1000 + 500) * 100).toLong())
        assertThat(mil.title).isEqualTo("Pagar")
    }

    @Test
    fun extensoCompostaAntesDaEscalaTambemSoma() {
        // "vinte e dois mil e 500 reais": o "e" de "vinte e dois" não pode ser comido pelo composto.
        val draft = parser.parse("pagar vinte e dois mil e 500 reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((22 * 1000 + 500) * 100).toLong())
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun extensoComParcelaEmDigitoEscalada() {
        // "dois mil e 500 mil reais": a parcela também é escalada — 2.000 + 500.000.
        // A leitura declarada é a mesma do par que já funciona ("dois mil e quinhentos mil reais").
        val draft = parser.parse("pagar dois mil e 500 mil reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((2 * 1000 + 500 * 1000) * 100).toLong())
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar quinhentos mil e 500 reais amanhã às 10h").amountCents)
            .isEqualTo(((500 * 1000 + 500) * 100).toLong())

        assertThat(parser.parse("pagar cem mil e 500 reais amanhã às 10h").amountCents)
            .isEqualTo(((100 * 1000 + 500) * 100).toLong())
    }

    @Test
    fun extensoComEscalaDeMilhaoEParcelaEmDigito() {
        // "pagar um milhão e 500 reais" gravava R$500,00 com o "milhão" no título.
        val draft = parser.parse("pagar um milhão e 500 reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((1_000_000 + 500) * 100).toLong())
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar dois milhões e 500 reais amanhã às 10h").amountCents)
            .isEqualTo(((2 * 1_000_000 + 500) * 100).toLong())

        // A escala sozinha ("milhão", sem o "um") vale o mesmo: é o mesmo escalar dito.
        assertThat(parser.parse("pagar milhão e 500 reais amanhã às 10h").amountCents)
            .isEqualTo(((1_000_000 + 500) * 100).toLong())
    }

    @Test
    fun parcelaEmDigitoComCentavos() {
        // O "e cinquenta centavos" fecha o valor depois da composição.
        val draft = parser.parse("pagar mil e 500 reais e cinquenta centavos amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo((1000 + 500) * 100 + 50)
        assertThat(draft.title).isEqualTo("Pagar")
    }

    // ---- Controles: o que já funcionava não pode regredir ----

    // ---- F1: o extenso+extenso com a escala de milhão não pode virar principal+parcela ----

    @Test
    fun extensoComExtensoComEscalaDeMilhaoContinuaUmNumeroSo() {
        // "pagar mil e quinhentos milhoes de reais" é UM número (1.500 milhões = 1,5 bilhão), não
        // um principal de 1×milhão mais uma parcela de 500.000. O ramo novo casava o extenso na
        // alternância de parcela, ancorava o principal opcional na escala sozinha e gravava
        // R$500.001.000,00 — `qc=true`, sem nota, confirmável em um toque.
        val draft = parser.parse("pagar mil e quinhentos milhoes de reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(1500L * 1_000_000 * 100)
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar dois mil e quinhentos milhoes de reais amanhã às 10h").amountCents)
            .isEqualTo(2500L * 1_000_000 * 100)
        assertThat(parser.parse("pagar mil e duzentos milhoes de reais amanhã às 10h").amountCents)
            .isEqualTo(1200L * 1_000_000 * 100)
    }

    @Test
    fun controleExtensoComExtensoContinuaSomando() {
        assertThat(parser.parse("pagar dois mil e quinhentos reais amanhã às 10h").amountCents)
            .isEqualTo(((2 * 1000 + 500) * 100).toLong())
        assertThat(parser.parse("pagar mil e quinhentos reais amanhã às 10h").amountCents)
            .isEqualTo(((1000 + 500) * 100).toLong())
        assertThat(parser.parse("pagar três mil e duzentos reais amanhã às 10h").amountCents)
            .isEqualTo(((3 * 1000 + 200) * 100).toLong())
        assertThat(parser.parse("pagar um milhão e duzentos mil reais amanhã às 10h").amountCents)
            .isEqualTo(((1_000_000 + 200 * 1000) * 100).toLong())
        assertThat(parser.parse("pagar duas mil e trezentas reais amanhã às 10h").amountCents)
            .isEqualTo(((2 * 1000 + 300) * 100).toLong())
    }

    @Test
    fun controleDigitoPrimeiroContinuaSomando() {
        // O que o #91 consertou, do outro lado.
        assertThat(parser.parse("pagar 2 mil e 500 reais amanhã às 10h").amountCents)
            .isEqualTo(((2 * 1000 + 500) * 100).toLong())
        assertThat(parser.parse("pagar 5 mil e vinte reais amanhã às 10h").amountCents)
            .isEqualTo(((5 * 1000 + 20) * 100).toLong())
        assertThat(parser.parse("pagar R$ 5 mil e cinquenta centavos amanhã às 10h").amountCents)
            .isEqualTo((5 * 1000) * 100 + 50)
        assertThat(parser.parse("pagar 5 mil reais e vinte amanhã às 10h").amountCents).isNull()
    }

    @Test
    fun controleDigitoMalformadoAntesDaEscalaContinuaSemValor() {
        // O motivo do guard original do #91: "30.50 mil reais" não pode virar R$1.000,00. O ramo
        // novo não pode abrir esta porta nem na versão composta da frase.
        val simples = parser.parse("pagar 30.50 mil reais amanhã às 10h")
        assertThat(simples.amountCents).isNull()
        assertThat(simples.title).contains("30.50")

        val composta = parser.parse("pagar 30.50 mil e 500 reais amanhã às 10h")
        assertThat(composta.amountCents).isNull()
        assertThat(composta.title).contains("30.50")

        // E o extenso+extenso com escala de milhão não pode reabrir a porta pelo outro lado.
        val milhoes = parser.parse("pagar 30.50 mil e quinhentos milhoes de reais amanhã às 10h")
        assertThat(milhoes.amountCents).isNull()
        assertThat(milhoes.title).contains("30.50")
    }

    @Test
    fun semUnidadeDeDinheiroContinuaSemValor() {
        // Sem "reais" não há o que distinguir entre pagar e contar — o valor fica nulo.
        assertThat(parser.parse("pagar mil e 500 amanhã às 10h").amountCents).isNull()
        assertThat(parser.parse("pagar dois mil e 500 amanhã às 10h").amountCents).isNull()
    }

    @Test
    fun conectorQueNaoFechaOCentavoContinuaSemValor() {
        assertThat(parser.parse("pagar mil e 500 reais e vinte amanhã às 10h").amountCents).isNull()
    }
}

package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * O valor em reais quando a fala MISTURA dígito e extenso ("2 mil e quinhentos reais") e quando os
 * centavos ditos passam de 99 ("5 reais e 100 centavos").
 *
 * Os dois defeitos gravam dinheiro que ela não falou, ou somem com o que ela falou, e a caixa
 * rápida confirma em um toque. O esperado de cada caso é aritmética escrita à mão — nunca derivada
 * da lógica do parser.
 */
class LocalTaskParserValorMistoECentavosTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    // ---- D1: o valor composto — dígito escalado + a parcela dita depois do "e" ----

    @Test
    fun valorMistoComParcelaEmDigitoSomaAsParcelas() {
        // "pagar 2 mil e 500 reais" gravava R$500,00 (o ramo numérico re-ancorava no "500 reais") e
        // ainda deixava o "2 mil" no título: o valor era 5x menor e saía sem nota.
        val draft = parser.parse("pagar 2 mil e 500 reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((2 * 1000 + 500) * 100).toLong())
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar 3 mil e 20 reais amanhã às 10h").amountCents)
            .isEqualTo(((3 * 1000 + 20) * 100).toLong())
    }

    @Test
    fun valorMistoComParcelaPorExtensoNaoFicaNulo() {
        // "pagar 2 mil e quinhentos reais" ficava sem valor nenhum: o extenso casava só o
        // "mil e quinhentos" e o guard do dígito anterior recusava — o certo são R$2.500,00.
        val draft = parser.parse("pagar 2 mil e quinhentos reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((2 * 1000 + 500) * 100).toLong())
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar 1 mil e quinhentos reais amanhã às 10h").amountCents)
            .isEqualTo(((1 * 1000 + 500) * 100).toLong())

        assertThat(parser.parse("pagar 5 mil e duzentos e cinquenta reais amanhã às 10h").amountCents)
            .isEqualTo(((5 * 1000 + 200 + 50) * 100).toLong())
    }

    @Test
    fun valorMistoComEscalaDeMilhao() {
        // "pagar 2 milhoes e 500 mil reais" gravava R$500.000,00 — a parcela escalada sozinha.
        val draft = parser.parse("pagar 2 milhoes e 500 mil reais amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((2 * 1_000_000 + 500 * 1_000) * 100).toLong())
        assertThat(draft.title).isEqualTo("Pagar")
    }

    @Test
    fun digitoRecusadoAntesDoExtensoContinuaSemViralizar() {
        // O motivo do guard original: "30.50 mil reais" — o ponto não é milhar, o ramo numérico
        // recusa, e o extenso re-ancorava no "mil reais" e gravava R$1.000,00. A correção do valor
        // composto não pode abrir esta porta.
        val draft = parser.parse("pagar 30.50 mil reais amanhã às 10h")
        assertThat(draft.amountCents).isNull()
        assertThat(draft.title).contains("30.50")
    }

    // ---- D2: centavos ditos ≥ 100 ----

    @Test
    fun centavosAcimaDeNoventaENoveSomamAoValorESaemDoTitulo() {
        // "pagar 5 reais e 100 centavos" gravava R$5,00 e deixava o "100 centavos" no título.
        val draft = parser.parse("pagar 5 reais e 100 centavos amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((5 * 100 + 100)).toLong())
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar 2 reais e 150 centavos amanhã às 10h").amountCents)
            .isEqualTo(((2 * 100 + 150)).toLong())
    }

    @Test
    fun centavosAcimaDeNoventaENovePorExtenso() {
        // "pagar cinco reais e cem centavos" gravava R$5,00: o guard 0..99 recusava o "cem".
        assertThat(parser.parse("pagar cinco reais e cem centavos amanhã às 10h").amountCents)
            .isEqualTo(((5 * 100 + 100)).toLong())
        assertThat(parser.parse("pagar cinco reais e cento e cinquenta centavos amanhã às 10h").amountCents)
            .isEqualTo(((5 * 100 + 100 + 50)).toLong())
    }

    @Test
    fun centavosAbaixoDeCemContinuamSomando() {
        // O caminho que já funcionava não pode regredir.
        val draft = parser.parse("pagar quinze reais e cinquenta centavos amanhã às 10h")
        assertThat(draft.amountCents).isEqualTo(((15 * 100 + 50)).toLong())
        assertThat(draft.title).isEqualTo("Pagar")

        assertThat(parser.parse("pagar 15 reais e 50 centavos amanhã às 10h").amountCents)
            .isEqualTo(((15 * 100 + 50)).toLong())
    }
}

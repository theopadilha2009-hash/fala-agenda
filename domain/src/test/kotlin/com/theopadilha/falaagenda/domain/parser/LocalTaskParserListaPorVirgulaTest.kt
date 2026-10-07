package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A lista falada por vírgula ("comprar pão, leite") não pode perder item do título.
 *
 * O `extractTitle` limpa o token com `.trim(',', '.', '!', '?')` ao montar o casamento, mas o
 * `leftover` ainda guardava a palavra com a vírgula colada — nenhum casamento, e o item sumia do
 * título em silêncio (`ambiguous=false`, sem nota). É uma lista de compras: ela fala três coisas e
 * o app guardava uma.
 */
class LocalTaskParserListaPorVirgulaTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val parser = LocalTaskParser(
        FixedAppClock(LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(), zone),
    )

    @Test
    fun listaDeDoisItensMantemOsDois() {
        val draft = parser.parse("comprar pão, leite")

        assertThat(draft.title).isEqualTo("Comprar pão leite")
    }

    @Test
    fun listaDeTresItensMantemOsTres() {
        val draft = parser.parse("comprar pão, leite, ovos")

        assertThat(draft.title).isEqualTo("Comprar pão leite ovos")
    }

    @Test
    fun listaComEConectandoOsUltimosItens() {
        val draft = parser.parse("comprar leite, pão e ovos")

        assertThat(draft.title).isEqualTo("Comprar leite pão ovos")
    }

    @Test
    fun listaDeQuatroItensMantemOsQuatro() {
        val draft = parser.parse("comprar pão, leite, ovos e queijo")

        assertThat(draft.title).isEqualTo("Comprar pão leite ovos queijo")
    }

    @Test
    fun listaComDataEHoraNaoPerdeItemNemData() {
        val draft = parser.parse("comprar pão, leite amanhã às 10h")

        assertThat(draft.title).isEqualTo("Comprar pão leite")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(10, 0))
    }

    @Test
    fun pontuacaoDeFimDeFraseContinuaLimpa() {
        val draft = parser.parse("tomar remédio.")

        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun falaDeUmaTarefaSoContinuaIgual() {
        val draft = parser.parse("comprar leite")

        assertThat(draft.title).isEqualTo("Comprar leite")
    }

    @Test
    fun virgulaColadaJaPreservavaOsDoisItensENaoPodeRegredir() {
        // Com a vírgula colada não há token com pontuação na borda — o defeito do lote não ocorre
        // (o doc da caçada registra isso). O que este teste prende é o invariante: os dois itens
        // continuam no título. A vírgula interna é o token cru, fora do escopo deste lote.
        val draft = parser.parse("comprar pão,leite")

        assertThat(draft.title).isEqualTo("Comprar pão,leite")
    }
}

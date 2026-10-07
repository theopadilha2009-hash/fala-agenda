package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * O corpus da correção de data, gerado por FORMA GRAMATICAL — não por frases que soam naturais
 * para quem escreveu a lista de conectores.
 *
 * O #83 foi reprovado três vezes pelo mesmo vício: o corpus saía da mesma intuição do código, e
 * ficava verde com o defeito vivo. A pergunta que este arquivo faz é a inversa: **que forma esta
 * lista NÃO cobre?** Por isso as falas são o produto cartesiano de
 *
 *     prefixo × valor-descartado × conector × valor-corrigido
 *
 * e as formas do conector entram como classe, não como exemplo: com vírgula dos dois lados, com
 * a vírgula só antes, com a vírgula só depois, colado sem vírgula, e o sujeito explícito antes
 * da correção.
 *
 * O ORÁCULO É INDEPENDENTE. O esperado não sai de rodar o parser numa "frase de referência" —
 * isso seria o parser conferindo a si mesmo. Sai da doutrina do produto escrita à mão, na tabela
 * [DIA] / [HORA]: *ela disse o valor corrigido, então é o valor corrigido que vale*. A
 * aritmética do calendário está resolvida abaixo, à mão, com o relógio fixo declarado.
 *
 * Relógio fixo: **2026-08-20 10:00 America/Sao_Paulo**, uma quinta-feira.
 */
class LocalTaskParserCorrecaoDeDataCorpusTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    /**
     * O que ela diz → o que vale, escrito à mão. A coluna da direita é a doutrina: "ela disse
     * hoje, então é hoje".
     */
    private val DIA = listOf(
        "hoje" to LocalDate.of(2026, 8, 20),
        "amanha" to LocalDate.of(2026, 8, 21),
        "depois de amanha" to LocalDate.of(2026, 8, 22),
        "sabado" to LocalDate.of(2026, 8, 22),
        "domingo" to LocalDate.of(2026, 8, 23),
        "segunda" to LocalDate.of(2026, 8, 24),
        "terca" to LocalDate.of(2026, 8, 25),
        "quarta" to LocalDate.of(2026, 8, 26),
        "semana que vem" to LocalDate.of(2026, 8, 27),
        "dia 25" to LocalDate.of(2026, 8, 25),
        "dia 28" to LocalDate.of(2026, 8, 28),
    )

    private val HORA = listOf(
        "as oito" to LocalTime.of(8, 0),
        "as 9" to LocalTime.of(9, 0),
        "as dez" to LocalTime.of(10, 0),
        "meio-dia" to LocalTime.of(12, 0),
        "as tres" to LocalTime.of(3, 0),
        "as nove e meia" to LocalTime.of(9, 30),
    )

    /** Os prefixos: sujeito implícito, sujeito explícito, verbo de anotação. */
    private val PREFIXOS = listOf(
        "me lembra de tomar remedio ",
        "anota tomar remedio ",
        "tomar remedio ",
        "eu preciso tomar remedio ",
    )

    /**
     * As FORMAS do conector. É aqui que o corpus se separa da intuição do código: cada forma é
     * uma classe gramatical, e as que o código não cobre aparecem no relatório em vez de sumirem.
     */
    private val FORMAS = listOf(
        ", nao, ",
        ", nao ",
        " nao, ",
        ", quer dizer, ",
        ", quer dizer ",
        ", na verdade, ",
        ", digo, ",
        ", melhor, ",
        ", errei, ",
        ", em vez disso, ",
        ", ao inves disso, ",
    )

    /** Formas que a doutrina do produto manda valer, mas que a lista do código pode não cobrir. */
    private val FORMAS_EXIGIDAS = setOf(", nao, ", ", nao ", ", quer dizer, ", ", digo, ", ", melhor, ")

    @Test
    fun aCorrecaoDeDiaValeEmTodaFormaGramatical() {
        var casos = 0
        val violacoes = mutableListOf<String>()
        PREFIXOS.forEach { prefixo ->
            DIA.forEach { (descartado, _) ->
                DIA.forEach { (corrigido, esperado) ->
                    if (descartado == corrigido) return@forEach
                    FORMAS.forEach { forma ->
                        val fala = "$prefixo$descartado$forma$corrigido"
                        casos++
                        val obtido = parser.parse(fala).localDate
                        if (obtido != esperado) {
                            violacoes += "«$fala» descartado=$descartado corrigido=$corrigido " +
                                "esperado=$esperado obtido=$obtido"
                        }
                    }
                }
            }
        }
        println("CORPUS-DIA|casos=$casos|violacoes=${violacoes.size}")
        violacoes.take(12).forEach { println("CORPUS-VIOL|dia|$it") }
        assertThat(violacoes).isEmpty()
    }

    @Test
    fun aCorrecaoDeHoraValeEmTodaFormaGramaticalEODiaSobrevive() {
        var casos = 0
        val violacoes = mutableListOf<String>()
        PREFIXOS.forEach { prefixo ->
            HORA.forEach { (descartado, _) ->
                HORA.forEach { (corrigido, esperado) ->
                    if (descartado == corrigido) return@forEach
                    FORMAS.forEach { forma ->
                        val fala = "${prefixo}amanha $descartado$forma$corrigido"
                        casos++
                        val draft = parser.parse(fala)
                        val problemas = buildList {
                            if (draft.localTime != esperado) {
                                add("hora esperado=$esperado obtido=${draft.localTime}")
                            }
                            // O dia que ela NÃO corrigiu não pode sumir junto com a hora trocada.
                            if (draft.localDate != LocalDate.of(2026, 8, 21)) {
                                add("dia esperado=2026-08-21 obtido=${draft.localDate}")
                            }
                        }
                        if (problemas.isNotEmpty()) {
                            violacoes += "«$fala» ${problemas.joinToString("; ")}"
                        }
                    }
                }
            }
        }
        println("CORPUS-HORA|casos=$casos|violacoes=${violacoes.size}")
        violacoes.take(12).forEach { println("CORPUS-VIOL|hora|$it") }
        assertThat(violacoes).isEmpty()
    }

    /**
     * O relatório de cobertura por FORMA: o defeito não pode voltar pela forma que a lista não
     * cobre. Cada forma exigida pela doutrina tem de estar coberta — a que não estiver aparece
     * aqui, nomeada, em vez de passar em silêncio.
     */
    @Test
    fun todaFormaExigidaPelaDoutrinaEstaCoberta() {
        val descartado = "amanha"
        val corrigido = "hoje"
        val esperado = LocalDate.of(2026, 8, 20)
        val cobertura = FORMAS.associateWith { forma ->
            parser.parse("me lembra de tomar remedio $descartado$forma$corrigido").localDate == esperado
        }
        println("CORPUS-COBERTURA|$cobertura")
        val descobertas = FORMAS_EXIGIDAS.filterNot { cobertura[it] == true }
        println("CORPUS-DESCOBERTAS|${descobertas.ifEmpty { listOf("nenhuma") }}")
        assertThat(descobertas).isEmpty()
    }
}

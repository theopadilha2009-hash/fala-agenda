package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A correção que troca a DATA (e não o alvo): ela dita "amanhã às oito, não, hoje".
 *
 * Relógio fixo declarado: **2026-08-20 10:00 America/Sao_Paulo** (quinta-feira).
 *
 * O esperado de cada caso é escrito À MÃO, pela doutrina do produto — "ela disse o valor
 * corrigido, então é o valor corrigido" —, e a aritmética do calendário é feita aqui, fora do
 * parser:
 *
 * | dita        | vale       |
 * |-------------|------------|
 * | hoje        | 20/08/2026 |
 * | amanhã      | 21/08/2026 |
 * | segunda     | 24/08/2026 |
 * | terça       | 25/08/2026 |
 * | dia 25      | 25/08/2026 |
 * | dia 28      | 28/08/2026 |
 * | semana que vem | 27/08/2026 |
 *
 * Nenhum esperado é derivado de rodar o parser numa "frase de referência": o oráculo que se
 * calcula com o mesmo raciocínio do código não é oráculo, é espelho.
 */
class LocalTaskParserCorrecaoDeDataTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    private fun parse(fala: String) = parser.parse(fala)

    @Test
    fun controleSemCorrecaoNaoMuda() {
        val draft = parse("me lembra de tomar remédio amanhã às oito")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.ambiguous).isFalse()
    }

    /** O P0 do catálogo: ela corrige o dia para HOJE e o app agenda AMANHÃ, calado. */
    @Test
    fun correcaoDoDiaVenceODiaDescartado() {
        val draft = parse("me lembra de tomar remédio amanhã às oito, não, hoje")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaComAHoraDepoisDoConector() {
        val draft = parse("me lembra de tomar remédio amanhã, não, hoje às oito")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDaHoraNaoApagaAHora() {
        val draft = parse("me lembra de tomar remédio amanhã às 8, não, às 9")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaDaSemana() {
        val draft = parse("me lembra de tomar remédio segunda, não, terça")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 25))
        assertThat(draft.localTime).isNull()
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaDoMes() {
        val draft = parse("me lembra de tomar remédio dia 25, não, dia 28")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 28))
        assertThat(draft.localTime).isNull()
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDoDiaEDaHoraJuntos() {
        val draft = parse("me lembra de tomar remédio amanhã às 8, não, hoje às 9")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(9, 0))
        assertThat(draft.title).isEqualTo("Tomar remédio")
    }

    @Test
    fun correcaoDeDiaRelativo() {
        val draft = parse("me lembra de tomar remédio amanhã às oito, não, semana que vem")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 27))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
    }

    @Test
    fun correcaoDuplaValeAUltima() {
        val draft = parse("me lembra de tomar remédio amanhã às oito, não, segunda, não, hoje")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
    }

    /**
     * O "não" que FECHA a fala ("me lembra amanhã, não"): ela começou a se corrigir e parou.
     *
     * Não há valor novo nenhum para o app usar, então o "não" não é fronteira — o dia dito antes
     * continua valendo — e ele também não some: ele sobrevive no título, que é o que ela disse.
     * O título é a única diferença observável deste ramo, e é ela que prende o guard: sem a
     * asserção, remover o tratamento da cauda vazia deixava a suíte inteira verde.
     */
    @Test
    fun naoTerminalNaoDescartaODiaENaoSomeDoTitulo() {
        val draft = parse("me lembra de tomar remédio amanhã, não")
        assertThat(draft.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(draft.title).isEqualTo("Tomar remédio não")
    }

    // --- o outro lado: a CONTINUAÇÃO depois do "não" ---------------------------------
    //
    // A mesma fala que se corrige tem a irmã que CONTINUA: "não tomei hoje" não volta atrás de
    // nada, ela explica por que o lembrete existe. O "não" ali nega o que vem DEPOIS, e o dia
    // dito antes dele continua valendo.
    //
    // O gatilho do defeito é o VALOR TEMPORAL na cauda, não o verbo. Um critério que pergunte
    // "a cauda tem um verbo de tarefa?" responde por uma lista de 20 verbos e deixa de fora toda
    // a fala que não está nela — e aí o valor descartado vence calado, com `ambiguous=false` e a
    // caixa rápida confirmando o dia errado em um toque. A lista não é o critério; o valor é.

    /**
     * A tabela do defeito: verbo de continuação × valor temporal na cauda. Escrita à mão, com o
     * dia que ela disse (`amanhã` = 21/08) — o `hoje` da cauda é a RAZÃO, não a correção.
     */
    @Test
    fun continuacaoComValorNaCaudaNaoDescartaODia() {
        val casos = listOf(
            "me lembra de tomar remédio amanhã às oito, não tomei hoje",
            "me lembra de tomar remédio amanhã, não tomei hoje",
            "anota pagar a conta amanhã, não deu hoje",
            "me lembra de ligar pro médico amanhã, não consegui hoje",
        )
        val violacoes = casos.mapNotNull { fala ->
            val draft = parse(fala)
            if (draft.localDate == LocalDate.of(2026, 8, 21)) {
                null
            } else {
                "«$fala» esperado=2026-08-21 obtido=${draft.localDate} amb=${draft.ambiguous}"
            }
        }
        assertThat(violacoes).isEmpty()
    }

    /**
     * O produto: **20 verbos de continuação × 10 valores temporais**, todos com o dia dito antes
     * do "não" — nenhum pode perder o dia. O que o teste mede é a CLASSE, não os quatro exemplos
     * da tabela: um critério que responda por lista de verbos falha aqui em qualquer verbo que
     * ele não conheça.
     */
    @Test
    fun nenhumVerboDeContinuacaoComValorNaCaudaDescartaODia() {
        val verbos = listOf(
            "tomei", "tomo", "deu", "consegui", "pude", "quero", "preciso", "tenho",
            "vou", "sei", "fui", "paguei", "marquei", "liguei", "vi", "soube",
            "achei", "encontrei", "recebi", "lembrei",
        )
        val valores = listOf(
            "hoje", "hoje as oito", "amanha", "ontem", "agora",
            "de manha", "de tarde", "de noite", "mais tarde", "hoje a tarde",
        )
        val violacoes = mutableListOf<String>()
        var casos = 0
        verbos.forEach { verbo ->
            valores.forEach { valor ->
                val fala = "me lembra de tomar remedio amanha, nao $verbo $valor"
                casos++
                val draft = parse(fala)
                if (draft.localDate != LocalDate.of(2026, 8, 21)) {
                    violacoes += "«$fala» obtido=${draft.localDate} amb=${draft.ambiguous}"
                }
            }
        }
        println("CONTINUACAO|casos=$casos|violacoes=${violacoes.size}")
        violacoes.take(12).forEach { println("CONTINUACAO-VIOL|$it") }
        assertThat(violacoes).isEmpty()
    }
}

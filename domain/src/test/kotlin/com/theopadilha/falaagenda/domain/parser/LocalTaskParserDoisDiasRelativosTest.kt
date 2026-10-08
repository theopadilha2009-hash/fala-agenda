package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Dois dias relativos numa fala ("hoje e amanhã às 9h") não podem virar um dia só, calado.
 *
 * O oráculo destes testes NÃO sai do parser: sai da doutrina à mão — ela disse dois dias, e o
 * modelo do rascunho tem UM `localDate`. Logo não existe "a" data, e cravar uma delas em silêncio é
 * o defeito; o desfecho legítimo é o rascunho escalar (ambíguo), como já acontece quando a fala tem
 * verbo de tarefa ("tomar remédio hoje e amanhã às 9h" já marca ambíguo hoje).
 *
 * O que a doutrina NÃO manda escalar está preso por asserção aqui, porque a régua larga demais é
 * pior que a pergunta: repetir o MESMO dia ("hoje e hoje") é um dia, e um dia NEGADO ("amanhã, não
 * hoje") não é um segundo dia dito. As duas colunas foram medidas no commit base antes de o fix
 * existir, e o valor esperado de cada uma é o do base.
 *
 * Relógio e fuso fixos e declarados: quinta-feira, 20/08/2026, 10:00 em America/Sao_Paulo.
 */
class LocalTaskParserDoisDiasRelativosTest {
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val hoje: LocalDate = LocalDate.of(2026, 8, 20)
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    /** O pressuposto do relógio: 20/08/2026 é quinta. Se não for, a medição inteira mente. */
    @Test
    fun oRelogioFixoEDeQuinta() {
        assertThat(clock.today()).isEqualTo(hoje)
        assertThat(hoje.dayOfWeek).isEqualTo(DayOfWeek.THURSDAY)
    }

    // ---------------------------------------------------------------- Passo 1: o espaço, asserido

    private data class Linha(val fala: String, val data: LocalDate?, val ambigua: Boolean)

    /**
     * A medição que antes só imprimia, agora asserida. Uma linha que sempre passa não vale nada —
     * foi ela que imprimiu `"hoje e daqui a dois dias dentista às 9h" -> qc=true` sem acusar nada.
     */
    @Test
    fun oEspacoInteiroTemODesfechoDaDoutrina() {
        val esperado = listOf(
            // Dois dias DISTINTOS, inclusive os que o ramo de dia relativo consumia com `return`
            // cedo antes de o guard olhar. Escalam.
            Linha("hoje e daqui a dois dias dentista às 9h", null, true),
            Linha("hoje e daqui a duas semanas dentista às 9h", null, true),
            Linha("hoje e amanhã no fim do mês", null, true),
            Linha("hoje e amanhã meio do mês", null, true),
            Linha("hoje e depois de amanhã dentista às 9h", null, true),
            Linha("amanhã e daqui a dois dias dentista às 9h", null, true),
            Linha("hoje e amanhã às 9h", null, true),
            Linha("dentista hoje e amanhã às 9h", null, true),
            Linha("amanhã e depois de amanhã às 9h", null, true),
            Linha("amanhã e depois de amanhã", null, true),
            Linha("hoje, amanhã e depois de amanhã", null, true),
            Linha("tomar remédio hoje e amanhã às 9h", null, true),

            // O MESMO dia repetido é UM dia: continua cravando, como no base.
            Linha("hoje e hoje", hoje, false),
            Linha("amanhã e amanhã", LocalDate.of(2026, 8, 21), false),
            Linha("hoje, hoje mesmo", hoje, false),
            Linha("amanhã, amanhã", LocalDate.of(2026, 8, 21), false),
            Linha("depois de amanhã e daqui a dois dias", LocalDate.of(2026, 8, 22), false),

            // O dia NEGADO não é um segundo dia dito: ela diz um e recusa o outro.
            Linha("amanhã, não hoje", LocalDate.of(2026, 8, 21), false),
            Linha("não hoje, amanhã", LocalDate.of(2026, 8, 21), false),
            Linha("hoje não, amanhã sim", LocalDate.of(2026, 8, 21), false),

            // Custo medido do lado seguro: "de hoje para amanhã" é locução ("logo"), não dois dias.
            // O base cravava 21/08 — inventava o dia; escalar é o desfecho honesto. Prendido aqui
            // para o número não subir (nem descer) calado.
            Linha("de hoje para amanhã", null, true),
            Linha("de hoje a oito dias dentista às 9h", hoje, false),

            // Um dia só, em todas as formas que já cravavam.
            Linha("amanhã", LocalDate.of(2026, 8, 21), false),
            Linha("hoje", hoje, false),
            Linha("depois de amanhã", LocalDate.of(2026, 8, 22), false),
            Linha("hoje às 9h", hoje, false),
            Linha("reunião amanhã", LocalDate.of(2026, 8, 21), false),
            Linha("almoço amanhã meio-dia", LocalDate.of(2026, 8, 21), false),
            Linha("dentista depois de amanhã às 9h30", LocalDate.of(2026, 8, 22), false),
            Linha("me lembrar de tomar remédio hoje às 21h", hoje, false),
            Linha("daqui a dois dias dentista às 9h", LocalDate.of(2026, 8, 22), false),

            // Relativo + dia nomeado / dia do mês / borda do mês: inalterados pelo guard.
            //
            // ATENÇÃO — "hoje e sexta" e "hoje e no dia 25" cravam `hoje` e descartam o outro dia
            // calado. É a MESMA classe de defeito, mas o segundo dia não é relativo (é dia da semana
            // e dia do mês), e alargar o guard até lá não é o escopo deste PR. Estas duas linhas
            // prendem o COMPORTAMENTO DO BASE, não endossam a doutrina: o valor esperado é o que o
            // base já fazia, para o número não mudar calado. A doutrina pediria escalar.
            Linha("hoje e sexta dentista às 9h", hoje, false),
            Linha("amanhã e sexta dentista às 9h", LocalDate.of(2026, 8, 21), false),
            Linha("hoje e no dia 25 dentista às 9h", hoje, false),
            // Estes dois JÁ escalam pela doutrina de dois dias da semana ("sexta e sábado",
            // "segunda e quarta") — o guard novo não os toca.
            Linha("sexta e sábado às 9h", null, true),
            Linha("segunda e quarta", null, true),
            Linha("toda segunda e quarta natação às 18h", LocalDate.of(2026, 8, 24), false),
        )
        for (linha in esperado) {
            val d = parser.parse(linha.fala)
            assertThat(d.localDate).isEqualTo(linha.data)
            assertThat(d.ambiguous).isEqualTo(linha.ambigua)
            if (linha.ambigua) {
                assertThat(d.canQuickConfirm(clock.instant(), zone)).isFalse()
            }
        }
    }

    // ---------------------------------------------------------------- Passo 2: o fix

    /**
     * O núcleo do defeito: dois dias relativos ditos na MESMA oração. O primeiro dia casado era
     * consumido com `return` cedo e o segundo nunca era olhado — a fala virava um dia, completa e
     * confirmável num toque, com o dia descartado sobrando no título.
     *
     * A asserção de `localDate == null` é a que prende o "não inventa um dia": sem ela, um rascunho
     * que crava `today.plusDays(1)` mantendo `ambiguous = true` e a nota passa a suíte inteira.
     */
    @Test
    fun doisDiasRelativosNaMesmaOracaoEscalam() {
        val falas = listOf(
            "hoje e amanhã às 9h",
            "dentista hoje e amanhã às 9h",
            "amanhã e depois de amanhã às 9h",
            "amanhã e depois de amanhã",
            "hoje, amanhã e depois de amanhã",
        )
        for (fala in falas) {
            val d = parser.parse(fala)
            assertThat(d.ambiguous).isTrue()
            assertThat(d.localDate).isNull()
            assertThat(d.notes.joinToString()).contains(NotasDoRascunho.DATA_AMBIGUA)
            assertThat(d.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    /**
     * O defeito do enunciado, palavra por palavra: ela diz DOIS dias e o segundo vinha de uma
     * expressão que o ramo de dia relativo resolvia com `return` cedo — o guard estava depois dele
     * e nunca via a fala. `"hoje e daqui a dois dias"` saía 22/08, `amb=false`, `qc=true`, sem nota.
     */
    @Test
    fun doisDiasDistintosEscalamAindaQueORamoDevolvaCedo() {
        val falas = listOf(
            "hoje e daqui a dois dias dentista às 9h",
            "hoje e daqui a duas semanas dentista às 9h",
            "amanhã e daqui a dois dias dentista às 9h",
            "hoje e amanhã no fim do mês",
            "hoje e amanhã meio do mês",
            "hoje e depois de amanhã dentista às 9h",
        )
        for (fala in falas) {
            val d = parser.parse(fala)
            assertThat(d.localDate).isNull()
            assertThat(d.ambiguous).isTrue()
            assertThat(d.notes.joinToString()).contains(NotasDoRascunho.DATA_AMBIGUA)
            assertThat(d.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    /** O primeiro dia dito não pode sobrar como título: "Hoje", "Dentista hoje", "Amanhã". */
    @Test
    fun oDiaDescartadoNaoViraTitulo() {
        assertThat(parser.parse("hoje e amanhã às 9h").title).isEmpty()
        assertThat(parser.parse("dentista hoje e amanhã às 9h").title).isEqualTo("Dentista")
        assertThat(parser.parse("amanhã e depois de amanhã às 9h").title).isEmpty()
        assertThat(parser.parse("amanhã e depois de amanhã").title).isEmpty()
    }

    /**
     * Repetir o MESMO dia é UM dia. O critério é o CONJUNTO de dias ditos, não a contagem de
     * marcadores: contando marcadores, "hoje e hoje" escalava e o app deixava de cravar o que já
     * cravava. A coluna da direita é o valor do base — medida, não deduzida.
     */
    @Test
    fun repetirOMesmoDiaContinuaCravando() {
        val mesmas = mapOf(
            "hoje e hoje" to hoje,
            "hoje, hoje mesmo" to hoje,
            "amanhã e amanhã" to LocalDate.of(2026, 8, 21),
            "amanhã, amanhã" to LocalDate.of(2026, 8, 21),
            "depois de amanhã e daqui a dois dias" to LocalDate.of(2026, 8, 22),
        )
        for ((fala, dia) in mesmas) {
            val d = parser.parse(fala)
            assertThat(d.localDate).isEqualTo(dia)
            assertThat(d.ambiguous).isFalse()
        }
    }

    /**
     * Um dia dito e o outro RECUSADO ("amanhã, não hoje") é um dia só — o "não" colado no marcador
     * o recusa, não o afirma. O base cravava o dia; contar o negado como segundo dia trocava uma
     * resposta certa por uma pergunta.
     */
    @Test
    fun oDiaNegadoNaoContaComoSegundoDia() {
        val negadas = mapOf(
            "amanhã, não hoje" to LocalDate.of(2026, 8, 21),
            "não hoje, amanhã" to LocalDate.of(2026, 8, 21),
            "hoje não, amanhã sim" to LocalDate.of(2026, 8, 21),
        )
        for ((fala, dia) in negadas) {
            val d = parser.parse(fala)
            assertThat(d.localDate).isEqualTo(dia)
            assertThat(d.ambiguous).isFalse()
        }
    }

    /** Um dia só continua sendo um dia só — a régua larga demais é pior que a pergunta. */
    @Test
    fun umDiaRelativoSozinhoContinuaCravando() {
        val amanha = parser.parse("amanhã às 9h")
        assertThat(amanha.localDate).isEqualTo(LocalDate.of(2026, 8, 21))
        assertThat(amanha.ambiguous).isFalse()

        val hojeSozinho = parser.parse("hoje às 9h")
        assertThat(hojeSozinho.localDate).isEqualTo(hoje)
        assertThat(hojeSozinho.ambiguous).isFalse()

        val depois = parser.parse("depois de amanhã às 9h")
        assertThat(depois.localDate).isEqualTo(LocalDate.of(2026, 8, 22))
        assertThat(depois.ambiguous).isFalse()
    }

    /** O controle que já escalava pelo verbo de tarefa tem de continuar escalando. */
    @Test
    fun comVerboDeTarefaContinuaEscalando() {
        val d = parser.parse("tomar remédio hoje e amanhã às 9h")
        assertThat(d.ambiguous).isTrue()
        assertThat(d.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    /** "e" de número não é dois dias: "vinte e cinco de novembro" é UMA data. */
    @Test
    fun eDeNumeroNaoViraDoisDias() {
        val d = parser.parse("consulta vinte e cinco de novembro às 9h")
        assertThat(d.localDate).isEqualTo(LocalDate.of(2026, 11, 25))
        assertThat(d.ambiguous).isFalse()
    }

    /**
     * O pêndulo: a régua larga demais é pior que a pergunta. Estas falas dizem UM dia só e têm de
     * continuar sendo cravadas sem ambiguidade — nenhuma delas pode ser derrubada pelo guard novo.
     */
    @Test
    fun oCorpusDeUmDiaSoNaoRegride() {
        val umDiaSo = listOf(
            "amanhã às 9h",
            "hoje às 9h",
            "depois de amanhã às 9h",
            "reunião amanhã",
            "almoço amanhã meio-dia",
            "dentista depois de amanhã às 9h30",
            "tomar remédio hoje às 21h",
            "sexta buscar as crianças às 17h",
            "toda segunda e quarta natação às 18h",
            "consulta 3 de novembro às 8h",
            "prova 25/12 às 09:30",
            "daqui a dois dias dentista às 9h",
            "semana que vem reunião às 10h",
            "todo dia às oito",
            "pagar contas todo dia 15 do mês às 10h",
        )
        for (fala in umDiaSo) {
            val d = parser.parse(fala)
            assertThat(d.ambiguous).isFalse()
        }
    }
}

package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Dois dias relativos numa fala ("hoje e amanhã às 9h") não podem virar um dia só, calado.
 *
 * O oráculo destes testes NÃO sai do parser: sai da doutrina à mão — ela disse dois dias, e o
 * modelo do rascunho tem UM `localDate`. Logo não existe "a" data, e cravar uma delas em silêncio é
 * o defeito; o desfecho legítimo é o rascunho escalar (ambíguo), como já acontece quando a fala tem
 * verbo de tarefa ("tomar remédio hoje e amanhã às 9h" já marca ambíguo hoje).
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

    // ---------------------------------------------------------------- Passo 1: medição crua

    private fun medir(fala: String): String {
        val d = parser.parse(fala)
        return buildString {
            append("\"").append(fala).append("\"")
            append(" -> title=\"").append(d.title).append("\"")
            append(" date=").append(d.localDate)
            append(" time=").append(d.localTime)
            append(" isComplete=").append(d.isComplete)
            append(" qc=").append(d.canQuickConfirm(clock.instant(), zone))
            append(" amb=").append(d.ambiguous)
            append(" notes=").append(d.notes)
        }
    }

    @Test
    fun medicaoDoEspacoInteiro() {
        println("===== MEDICAO: dois dias relativos (hoje=quinta 2026-08-20, 10:00) =====")
        listOf(
            "hoje e amanhã às 9h",
            "dentista hoje e amanhã às 9h",
            "amanhã e depois de amanhã às 9h",
            "amanhã e depois de amanhã",
            "hoje, amanhã e depois de amanhã",
            "--- controles que NAO podem regredir ---",
            "amanhã",
            "hoje",
            "depois de amanhã",
            "tomar remédio hoje e amanhã às 9h",
            "sexta e sábado às 9h",
            "segunda e quarta",
            "--- casos legitimos a preservar ---",
            "almoço amanhã meio-dia",
            "me lembrar de tomar remédio hoje às 21h",
            "toda segunda e quarta natação às 18h",
            "dentista depois de amanhã às 9h30",
            "reunião amanhã",
            "hoje às 9h",
            "--- vizinhos do defeito: outras combinacoes de dois dias ---",
            "hoje e daqui a dois dias dentista às 9h",
            "daqui a dois dias dentista às 9h",
            "hoje e sexta dentista às 9h",
            "amanhã e sexta dentista às 9h",
            "hoje e no dia 25 dentista às 9h",
        ).forEach { println(medir(it)) }
        println("===== FIM =====")
    }

    // ---------------------------------------------------------------- Passo 2: o fix

    /**
     * O núcleo do defeito: dois dias relativos ditos na MESMA oração. O primeiro dia casado era
     * consumido com `return` cedo e o segundo nunca era olhado — a fala virava um dia, completa e
     * confirmável num toque, com o dia descartado sobrando no título.
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

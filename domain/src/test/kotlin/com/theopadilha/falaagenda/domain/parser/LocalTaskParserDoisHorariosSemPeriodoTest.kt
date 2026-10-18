package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Duas horas ditas na primeira tomada SEM período próprio ("às tres e 8 da noite").
 *
 * Ela disse DUAS horas (3 e 8 da noite) e o parser cravava 03:08 com `ambiguous=false` — e, com
 * data na fala, `qc=true`: a caixa "Pode salvar?" oferecia um horário que ninguém falou. O
 * "da noite" chegava pela CAUDA do "e <num>" (`TRAILING_PERIOD`), fabricava um período para a
 * primeira hora sem alcançá-la (`applyPeriodHour` não move 1–6 para a noite), e o "e 8" entrava
 * de MINUTO pela `trailingMinutes`.
 *
 * A doutrina é a da 24a leva (`cacada-fala-2026-10-18-dois-horarios-sem-periodo.md`): o período
 * vindo da cauda que NÃO alcança a hora cravada invalida a leitura de minuto — o "e <num> da
 * noite" é uma segunda tomada, e o desfecho é ambíguo com `time=null`.
 *
 * O par que prende o critério é a coerência período↔hora, não o valor nem a estrutura:
 * "às tres e 8 da noite" (03:08 ∉ noite → ambíguo) × "às tres e 8 da manha" (03:08 ∈ manhã →
 * cravado). Os controles abaixo são os da própria caçada — nenhum pode regredir.
 */
class LocalTaskParserDoisHorariosSemPeriodoTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    // ---- O par discriminador ----

    @Test
    fun periodoDaCaudaQueNaoAlcancaInvalidaOMinuto() {
        val draft = parser.parse("tomar remédio às tres e 8 da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
    }

    @Test
    fun aNotaDoDesfechoEDeDoisHorarios() {
        val draft = parser.parse("tomar remédio às tres e 8 da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.notes.joinToString(" ")).contains("dois horários")
    }

    @Test
    fun comDataANaoCRavaSobrevive() {
        // O dano máximo da base: com o "todo dia" a data existe, `isComplete` fechava e a caixa
        // rápida confirmava 03:08 em um toque. Depois do fix o horário não existe para confirmar.
        val draft = parser.parse("tomar remédio todo dia às tres e 8 da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    @Test
    fun oMesmoDefeitoComDigitoEOutrasHorasDeMadrugada() {
        // Dígito e as outras horas que a noite não alcança: o "e 8" vira minuto e sobra 02:08/05:08
        // cravados numa noite que não os contém.
        listOf(
            "tomar remédio às 3 e 8 da noite",
            "tomar remédio às duas e 8 da noite",
            "tomar remédio às cinco e 8 da noite",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
        }
    }

    @Test
    fun periodoQueAlcancaMantemMinutoLegitimo() {
        // O OUTRO lado do par: mesma estrutura, mesmo horário cravado, mesmo "e 8" — só o período
        // difere, e 03:08 da MANHÃ é coerente. Se as duas frases dessem o mesmo desfecho, o
        // critério não seria a coerência que a caçada mediu.
        val draft = parser.parse("tomar remédio às tres e 8 da manha")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(3, 8))
        assertThat(draft.ambiguous).isFalse()
    }

    // ---- Controles travados (caçada 24a leva) ----

    @Test
    fun controlesQueAlcancamContinuamCravando() {
        // O período da cauda ALCANÇA a hora: 8→20, 3→15, 12→0. O minuto da mesma tomada segue
        // somando — é o que a caçada travou.
        listOf(
            "tomar remédio às 8 e meia da noite" to LocalTime.of(20, 30),
            "tomar remédio às 8 e 20 da noite" to LocalTime.of(20, 20),
            "tomar remédio às 3 e meia da tarde" to LocalTime.of(15, 30),
            "tomar remédio às 9 e 8 da noite" to LocalTime.of(21, 8),
            "tomar remédio às 12 e 8 da noite" to LocalTime.of(0, 8),
        ).forEach { (frase, esperado) ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isEqualTo(esperado)
            assertThat(draft.ambiguous).isFalse()
        }
    }

    @Test
    fun semPeriodoNaCaudaContinuaMinuto() {
        // Sem período nenhum na cauda, o "e 12" é o minuto de sempre ("às 8 e 12" = 08:12).
        val draft = parser.parse("tomar remédio às 8 e 12")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 12))
    }

    @Test
    fun eDistanteNaoAbreSegundaTomada() {
        // "depois" entre o "e" e o período: o "da noite" não é da cauda do relógio — pertence a
        // outra oração, e a primeira hora (08:00) segue cravada.
        val draft = parser.parse("tomar remédio às 8 da manhã e depois e 8 da noite")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(8, 0))
    }

    @Test
    fun aFracaoDeMinutoSegundaAMadrugada() {
        // "meia" nunca é hora: a leitura B4 travada ("às 3 e meia da noite" = 03:30, madrugada)
        // continua de pé — o critério novo é para a cauda que PODERIA ser hora, não para a fração.
        val draft = parser.parse("tomar remédio às 3 e meia da noite")
        assertThat(draft.localTime).isEqualTo(LocalTime.of(3, 30))
    }
}

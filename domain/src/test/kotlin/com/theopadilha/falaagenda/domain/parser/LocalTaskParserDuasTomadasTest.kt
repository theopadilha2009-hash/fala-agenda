package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Duas tomadas no mesmo dia ("às 8 da manhã e 8 da noite") — a fala de recorrência mais comum de
 * quem toma remédio.
 *
 * O `TaskSeries` tem UM só `localTime`: não há onde guardar duas doses. O desfecho correto não é
 * escolher uma delas nem inventar uma terceira — é marcar AMBÍGUO com nota, como o app já faz
 * quando a hora não foi dita. O que não pode acontecer é a caixa "Pode salvar?" oferecer um horário
 * que ninguém falou e ela confirmar em um toque.
 *
 * O esperado de cada caso é a doutrina escrita à mão — "ela disse dois horários, o app não pode
 * cravar um terceiro" —, nunca derivada da lógica do parser. Os controles do fim são o outro lado:
 * uma hora só com o minuto dito ("às 8 e meia" = 08:30) é legítima e NÃO pode virar ambígua.
 */
class LocalTaskParserDuasTomadasTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    // ---- D1: a segunda tomada em DÍGITO sem o "às" — o caso relatado ----

    @Test
    fun duasTomadasEmDigitoNaoViramUmHorarioInventado() {
        // "às 8 da manhã e 8 da noite": o relógio acha UMA hora (só o primeiro "8" casa com o "às")
        // e o "e 8" virava o MINUTO dela — 08:08, sem nota e com qc=true. A segunda tomada (20h) não
        // existe em campo nenhum e a caixa rápida confirma 08:08 em um toque. Nem 08:08 nem 08:00:
        // ela disse DOIS horários e o modelo só guarda um.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e 8 da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.localTime).isNull()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        assertThat(draft.notes).isNotEmpty()

        // O mesmo com o dia da semana no lugar do "todo dia": o relógio devolvia 08:00 cravado, e a
        // caixa rápida confirmava segunda-feira 08:00 com qc=true para as duas doses.
        val segunda = parser.parse("tomar remédio toda segunda às 8 da manhã e 8 da noite")
        assertThat(segunda.ambiguous).isTrue()
        assertThat(segunda.localTime).isNull()
        assertThat(segunda.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    @Test
    fun aSegundaTomadaEmDigitoNaoViraMinutoDaPrimeira() {
        // O dano exato: o "8" da segunda tomada entrava no MINUTO da primeira. Qualquer número dito
        // depois do primeiro período é um horário novo, nunca o minuto de uma hora já fechada.
        listOf(
            "tomar remédio todo dia às 8 da manhã e 20 da noite",
            "tomar remédio todo dia às 8 da manhã e 15 da tarde",
            "tomar remédio todo dia às 8 da manhã e 12 da tarde",
            "tomar remédio todo dia às 8 da manhã e 30 da noite",
            "tomar remédio todo dia às 8 da manhã e 45 da noite",
            "tomar remédio todo dia às 8 da manhã e 5 da tarde",
            "tomar remédio às 8 da manhã e 8 da tarde",
            "tomar remédio às 8 da manhã e 8 da madrugada",
            "tomar remédio às 8 da manhã e 8 de noite",
            "tomar remédio às 8 da manhã e 8 na noite",
            "tomar remédio às 8 da noite e 8 da manhã",
            "tomar remédio às 8 da manhã e 8 da noite e 2 da tarde",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    @Test
    fun segundaTomadaPorExtensoNaoViraMinutoNemTitulo() {
        // "e oito da noite": o extenso não casava o `MINUTE_TAIL` numérico, então a hora ficava 08:00
        // e o "oito" ia para o TÍTULO — 20h perdida com o nome da tarefa poluído.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e oito da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.localTime).isNull()
        assertThat(draft.title).isEqualTo("Tomar remédio")
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()

        // "e vinte da noite": o "vinte" caía no `MINUTE_TAIL_WORDS` e virava 08:20.
        val vinte = parser.parse("tomar remédio todo dia às 8 da manhã e vinte da noite")
        assertThat(vinte.ambiguous).isTrue()
        assertThat(vinte.localTime).isNull()
        assertThat(vinte.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    // ---- D2: a segunda tomada sem hora dita — "e 8", "e 12" ----

    @Test
    fun numeroSoltoQueCabeComoHoraNaoViraMinuto() {
        // "às 8 da manhã e 8": não há "às" nem período na segunda parte, mas o número CABE como
        // hora (0–23) — é uma segunda tomada, não o minuto da primeira. A base cravava 08:08, um
        // horário que ela não falou, com qc=true.
        listOf(
            "tomar remédio todo dia às 8 da manhã e 8",
            "tomar remédio todo dia às 8 da manhã e 12",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    @Test
    fun numeroQueNaoCabeComoHoraContinuaSendoMinuto() {
        // O outro lado, e é aqui que o critério tem que parar: "e 30" e "e 45" NÃO cabem como hora,
        // então continuam sendo o minuto da mesma tomada (08:30, 08:45) — como sempre foram. Marcar
        // isso como dois horários derrubaria a forma como ela fala minuto, que é o motivo de o
        // `trailingMinutes` existir.
        assertThat(parser.parse("tomar remédio todo dia às 8 da manhã e 30").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio todo dia às 8 da manhã e 45").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 45))
    }

    // ---- D3: a segunda tomada em EXTENSO com "às" (a forma que já funcionava) ----

    @Test
    fun segundaTomadaComAsExplicitoContinuaAmbigua() {
        // O caminho que a caçada dizia estar certo: dois "às" ⇒ duas horas encontradas ⇒ ambíguo.
        // Não pode regredir para um horário cravado.
        val draft = parser.parse("tomar remédio todo dia às 8 da manhã e às 8 da noite")
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.localTime).isNull()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    // ---- Controles: o `trailingMinutes` existe por um motivo legítimo e não pode morrer ----

    @Test
    fun minutoDitoNaMesmaTomadaContinuaSomando() {
        // ESTE é o motivo do `trailingMinutes`: a hora e o minuto na MESMA tomada, sem período
        // entre eles. "às 8 e meia" são 08:30 — não dois horários. Uma regra que só olhasse o "e
        // <número>" derrubaria justamente a forma como ela fala minuto.
        assertThat(parser.parse("tomar remédio às 8 e meia").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8 e quinze").localTime).isEqualTo(java.time.LocalTime.of(8, 15))
        assertThat(parser.parse("tomar remédio às 8 e 15").localTime).isEqualTo(java.time.LocalTime.of(8, 15))
        assertThat(parser.parse("tomar remédio às 8 e 30").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8 e 45").localTime).isEqualTo(java.time.LocalTime.of(8, 45))
        assertThat(parser.parse("tomar remédio às 8 e 12").localTime).isEqualTo(java.time.LocalTime.of(8, 12))
        assertThat(parser.parse("tomar remédio às 8 e 5").localTime).isEqualTo(java.time.LocalTime.of(8, 5))
        assertThat(parser.parse("tomar remédio às 8 e 0").localTime).isEqualTo(java.time.LocalTime.of(8, 0))

        // Sem hora anterior (o "e meia" colado num relógio já formado) e por extenso.
        assertThat(parser.parse("tomar remédio às 8 horas e meia").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio oito e meia").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("me lembrar de tomar remédio amanhã às 9 e meia").localTime)
            .isEqualTo(java.time.LocalTime.of(9, 30))
        assertThat(parser.parse("consulta meio-dia e meia").localTime).isEqualTo(java.time.LocalTime.of(12, 30))
    }

    @Test
    fun minutoDitoNaMesmaTomadaComPeriodoDepoisContinuaSomando() {
        // "às 8 e meia da noite": UM horário só (20:30) — o minuto vem colado na hora e o período
        // vem depois dele. Não é duas tomadas, e não pode virar ambíguo.
        assertThat(parser.parse("tomar remédio às 8 e meia da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(20, 30))
        assertThat(parser.parse("tomar remédio às 8 e 30 da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(20, 30))
        assertThat(parser.parse("tomar remédio às 8 e 20 da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(20, 20))
        assertThat(parser.parse("tomar remédio às 3 e meia da tarde").localTime)
            .isEqualTo(java.time.LocalTime.of(15, 30))
        assertThat(parser.parse("dormir às 12 e meia da noite").localTime)
            .isEqualTo(java.time.LocalTime.of(0, 30))
    }

    @Test
    fun relogioEscritoComHhMmContinuaIgual() {
        // Os formatos com "h"/":" nem passam pelo `trailingMinutes`: a hora já vem fechada.
        assertThat(parser.parse("tomar remédio às 8:30").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8h30").localTime).isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8:30 da manhã").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 30))
        assertThat(parser.parse("tomar remédio às 8h30 da manhã").localTime)
            .isEqualTo(java.time.LocalTime.of(8, 30))
    }

    @Test
    fun periodoDaSegundaParteSemNumeroContinuaNaoInventando() {
        // "às 8 da manhã e de noite": a segunda parte só diz o período, sem número. O parser já
        // devolvia 08:00 sem ambiguidade; o certo é não cravar a segunda tomada — mas também não
        // inventar um minuto. Aqui o que se prende é o "não crava a noite calada".
        val draft = parser.parse("tomar remédio às 8 da manhã e de noite")
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }
}

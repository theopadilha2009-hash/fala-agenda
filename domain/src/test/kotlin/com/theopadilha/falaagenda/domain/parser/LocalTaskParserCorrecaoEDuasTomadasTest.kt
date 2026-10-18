package com.theopadilha.falaagenda.domain.parser

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * O CRUZAMENTO dos dois eixos: a correção na própria fala ("amanhã às oito, não, hoje") e as duas
 * tomadas no mesmo dia ("às 8 da manhã e 8 da noite").
 *
 * Cada eixo tem a sua suíte e as duas ficam verdes sozinhas — foi por isso que o composto mecânico
 * dos dois PRs pareceu seguro. Aqui as falas trazem os dois ao mesmo tempo, e é aí que a regra das
 * duas tomadas precisa saber de QUEM é o horário que ela está julgando.
 *
 * A doutrina, escrita à mão:
 *
 *  1. A correção é uma fronteira. O trecho que ela descartou não fala mais — nem para trazer um
 *     horário, nem para trazer a DÚVIDA de que havia dois.
 *  2. Dentro do trecho que ficou, dois horários continuam sendo dois horários: o app não crava um
 *     terceiro. A caixa rápida não pode oferecer um horário que ela nunca disse.
 *
 * O esperado de cada caso sai da frase, não do parser. O par que fecha o eixo é
 * `oEDescartadoNaoContamina` contra `oQueFicouAindaConta`: a MESMA segunda tomada, uma vez no
 * trecho descartado e uma vez no que vale.
 *
 * Medido nas três versões do parser (base `origin/main` @ be077ee, composto MECÂNICO dos dois PRs,
 * esta integração FIEL) sobre este mesmo arquivo:
 *
 * | versão   | falhas / 10 |
 * |----------|-------------|
 * | base     | 9           |
 * | mecânico | 6           |
 * | fiel     | 0           |
 *
 * No composto mecânico a regra das duas tomadas roda sobre a FALA INTEIRA (`semValor` derivado de
 * `working`): ela vê o "e 8 da noite" do trecho que a própria correção descartou e anula o horário
 * que ela acabou de escolher — ou pior, não vê o par que ficou, e o `trailingMinutes` crava 08:08
 * com `qc=true`. O corpus cruzado é a entrega: nenhuma suíte de um eixo só enxerga este defeito.
 */
class LocalTaskParserCorrecaoEDuasTomadasTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val parser = LocalTaskParser(clock)

    // ---- O trecho descartado não contamina o que ficou ----

    @Test
    fun oEDescartadoNaoContamina() {
        // "amanhã às 8 da manhã e 8 da noite" diz DOIS horários — mas ela voltou atrás e escolheu
        // hoje às 8 da manhã. O trecho que vale tem UM horário só, e ele é a resposta dela.
        val draft = parser.parse("tomar remédio amanhã às 8 da manhã e 8 da noite, não, hoje às 8 da manhã")
        assertThat(draft.localDate).isEqualTo(java.time.LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(draft.ambiguous).isFalse()
        // `canQuickConfirm` é falso aqui porque as 08:00 já passaram (o relógio do teste marca
        // 10:00) e a escolha nasceria sem aviso — regra alheia a este eixo. O que a fronteira
        // decide é o HORÁRIO e a ausência de ambiguidade; a hora futura fica no caso seguinte.
    }

    @Test
    fun oEDescartadoNaoContaminaComHoraFutura() {
        // A mesma fronteira, com o horário ainda por vir: agora a caixa rápida também tem que
        // voltar a oferecer a dose. No composto mecânico ela some — o rascunho fica ambíguo e sem
        // hora, e a dose do dia é perdida.
        val draft = parser.parse("tomar remédio amanhã às 8 da manhã e 8 da noite, não, hoje às 14")
        assertThat(draft.localDate).isEqualTo(java.time.LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(14, 0))
        assertThat(draft.ambiguous).isFalse()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isTrue()
    }

    @Test
    fun oQueFicouAindaConta() {
        // O outro lado do MESMO par: a segunda tomada agora está no trecho que vale. Nada mudou na
        // frase além de QUAL trecho carrega o "e 8 da noite" — e o desfecho tem que mudar junto.
        // Se as duas frases dessem o mesmo resultado, a fronteira de correção não estaria sendo
        // respeitada em uma delas.
        val draft = parser.parse("tomar remédio amanhã às 8 da manhã, não, hoje às 8 da manhã e 8 da noite")
        assertThat(draft.localDate).isEqualTo(java.time.LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
    }

    @Test
    fun aOrdemDosDoisHorariosNaoMudaODesfecho() {
        // Duas tomadas é duas tomadas, dita de manhã para noite ou de noite para manhã. O composto
        // mecânico acerta a segunda e erra a primeira — crava 08:08 (o "8" da tomada descartada
        // entrando no MINUTO da primeira), com `qc=true`, e ela confirma 08:08 num toque.
        listOf(
            "tomar remédio amanhã às 8 da manhã, não, hoje às 8 da manhã e 8 da noite",
            "tomar remédio amanhã às 8 da manhã, não, hoje às 8 da noite e 8 da manhã",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
            assertThat(draft.canQuickConfirm(clock.instant(), zone)).isFalse()
        }
    }

    @Test
    fun aRecorrenciaNaoMudaOCriterio() {
        // A mesma frase com "toda segunda" no lugar de "amanhã": a regra das duas tomadas não pode
        // depender de o trecho descartado ter data ou recorrência. O que decide é o horário, e ele
        // é do trecho que ficou.
        val descartado = parser.parse("tomar remédio toda segunda às 8 da manhã e 8 da noite, não, hoje às 8 da manhã")
        assertThat(descartado.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(descartado.ambiguous).isFalse()

        val valendo = parser.parse("tomar remédio toda segunda às 8 da manhã, não, hoje às 8 da manhã e 8 da noite")
        assertThat(valendo.localTime).isNull()
        assertThat(valendo.ambiguous).isTrue()
    }

    @Test
    fun correcaoSoDoDiaNaoMexeNoHorarioQueElaJaTinhaDito() {
        // O campo que ela NÃO corrigiu continua vindo do trecho descartado — é a doutrina do #99.
        // E o horário que veio de lá é um horário só: a regra das duas tomadas não tem o que pegar.
        val draft = parser.parse("tomar remédio amanhã às 8 da manhã, não, hoje")
        assertThat(draft.localDate).isEqualTo(java.time.LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    @Test
    fun aCorrecaoQueElaRecusouDeNovoNaoSome() {
        // Duas correções seguidas: só o ÚLTIMO trecho fala. O do meio — que dizia dois horários —
        // foi descartado duas vezes, e não pode voltar nem como valor nem como dúvida.
        val draft = parser.parse("tomar remédio amanhã às 8 da manhã e 8 da noite, não, hoje às 9 da manhã e 9 da noite, não, hoje às 10 da manhã")
        assertThat(draft.localDate).isEqualTo(java.time.LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(10, 0))
        assertThat(draft.ambiguous).isFalse()
    }

    // ---- As notas: duas causas no mesmo trecho convivem ----

    @Test
    fun asDuasNotasDoHorarioConvivem() {
        // O MESMO horário carrega dois problemas, e os dois precisam chegar à tela. O caso existe
        // pelo `INTERVAL_DAY` ("de 8 em 8 dias"): ele marca a hora incerta SEM apagar o período que
        // ela disse, então `hadPeriod` e `timeAmbiguous` são verdadeiros juntos — e a regra das duas
        // tomadas, que exige período, também dispara.
        //
        // Trocar uma nota pela outra apaga da tela justamente a razão que a outra dava. O rascunho
        // fica ambíguo sem dizer por quê, e as duas causas são independentes: a dose de 8 em 8 dias
        // E o segundo horário do dia.
        val draft = parser.parse("tomar remédio de 8 em 8 dias às 8 da manhã e 8 da noite")
        assertThat(draft.localTime).isNull()
        assertThat(draft.ambiguous).isTrue()
        val nota = draft.notes.joinToString(" ")
        assertThat(nota).contains("manhã ou de tarde")
        assertThat(nota).contains("dois horários")
    }

    // ---- Controles: o eixo vizinho não pode regredir ----

    @Test
    fun duasTomadasSemCorrecaoContinuaIgual() {
        // O caso puro do #102, sem conector nenhum: a integração não pode tê-lo perdido.
        listOf(
            "tomar remédio todo dia às 8 da manhã e 8 da noite",
            "tomar remédio às 8 da manhã e 8 de noite",
            "tomar remédio às 8 da noite e 8 da manhã",
        ).forEach { frase ->
            val draft = parser.parse(frase)
            assertThat(draft.localTime).isNull()
            assertThat(draft.ambiguous).isTrue()
        }
    }

    @Test
    fun aCorrecaoSozinhaContinuaIgual() {
        // O caso puro do #99, sem segunda tomada: o campo não corrigido vem do trecho descartado.
        val draft = parser.parse("tomar remédio amanhã às oito, não, hoje")
        assertThat(draft.localDate).isEqualTo(java.time.LocalDate.of(2026, 8, 20))
        assertThat(draft.localTime).isEqualTo(java.time.LocalTime.of(8, 0))
        assertThat(draft.ambiguous).isFalse()
    }
}

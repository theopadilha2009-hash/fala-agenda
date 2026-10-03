package com.theopadilha.falaagenda.domain.reminder

import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.recurrence.OccurrenceLifecycle
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * O que uma escolha manual (data, hora e regra) vira depois de salva.
 *
 * São as mesmas contas que o `TaskRepository` faz ao criar a primeira ocorrência, aqui para a
 * tela poder descrever o que vai acontecer em vez de descrever o que ela tocou. Isto existe
 * porque as duas contas já divergiram: a tela mostrava a data do seletor e o agendamento
 * nascia em outra, e o resumo prometia um dia que nunca virava ocorrência. Enquanto houver
 * duas contas, elas divergem de novo.
 */
object DraftSchedule {
    /**
     * A primeira ocorrência de uma série que nasce com esta escolha — e por que ela não é a
     * data escolhida, nulo quando é.
     *
     * O motivo faz parte da conta de propósito: a tela precisa explicar a data descartada, e
     * uma segunda versão do "por quê", escrita na UI, voltaria a divergir do que foi gravado.
     */
    data class FirstOccurrence(
        val date: LocalDate,
        val movedBecause: Reason?,
    ) {
        enum class Reason {
            /** A data escolhida não satisfaz a regra: o chip "Hoje" com "toda segunda". */
            RULE,

            /** O instante da escolha já passou e a regra repete: a primeira é a seguinte. */
            TIME_PASSED,
        }
    }

    /**
     * A data da primeira ocorrência de uma série que nasce com esta escolha.
     *
     * A data escolhida entra como **piso**, não como resposta: quem decide é a regra. Hoje é
     * terça, 29/09/2026, e ela fala "toda segunda natação às 18h" e toca no chip "Hoje": a
     * terça escolhida não satisfaz "toda segunda" e nunca vira ocorrência — nem no banco, nem
     * no `AlarmManager`. O primeiro aviso é segunda, 05/10/2026, e é essa data que a tela
     * precisa mostrar.
     *
     * E a regra que repete não pode nascer num instante já vencido: são 20:00 e ela escolhe
     * "todo dia às 18h" pelos chips. Instante no passado entregue ao `AlarmManager` dispara na
     * hora — o celular apitava no ato do cadastro e seguia a escada de repetições até o fim do
     * dia. A primeira ocorrência passa para a próxima data da regra, a mesma semântica que a
     * fala já usava (`LocalTaskParser`: sem data explícita e com regra recorrente, o horário
     * vencido de hoje empurra para a próxima data). Aqui as duas contas são uma só.
     *
     * A regra que **não** repete continua nascendo vencida, de propósito: ela é arquivada como
     * não realizada e sem alarme nenhum — quem decide isso é [bornWithoutReminder], e ver
     * `TaskRepository.saveDraft`.
     *
     * O `?: chosenDate` é inalcançável hoje — `firstOnOrAfter` só devolve nulo para a regra que
     * não repete com `seriesStart` antes do piso, e aqui os dois argumentos são a mesma data. Fica
     * como rede: se o motor ganhar um caso nulo novo, a tela mostra a data escolhida em vez de
     * estourar no meio de um toque.
     */
    fun firstOccurrence(
        rule: RecurrenceRule,
        chosenDate: LocalDate,
        chosenTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    ): FirstOccurrence {
        val byRule = RecurrenceEngine.firstOnOrAfter(rule, chosenDate, chosenDate) ?: chosenDate
        if (rule.kind == RecurrenceKind.NONE) return FirstOccurrence(byRule, null)
        val chosenAt = byRule.atTime(chosenTime).atZone(zoneId).toInstant()
        if (!chosenAt.isBefore(now)) {
            return FirstOccurrence(
                date = byRule,
                movedBecause = if (byRule == chosenDate) null else FirstOccurrence.Reason.RULE,
            )
        }
        // Vencida: a primeira é a próxima data da regra depois de hoje. Um passo só da regra
        // não bastaria — com a data escolhida já no passado ("dia 1º todo mês" dito no fim de
        // setembro) o passo seguinte também nasceria vencido e o alarme dispararia na hora de
        // novo. A partir de amanhã o instante é futuro para qualquer hora do dia.
        val today = OccurrenceLifecycle.todayIn(zoneId, now)
        val next = RecurrenceEngine.nextAfter(rule, chosenDate, today) ?: byRule
        return FirstOccurrence(next, FirstOccurrence.Reason.TIME_PASSED)
    }

    /**
     * A escolha já passou e não repete: a ocorrência nasce arquivada como não realizada e
     * **nenhum alarme é criado** — o aviso que a tela prometer não vai existir. É o caso dos
     * chips manuais, e não do parser: "Só uma vez" com o horário das 08:00 às 15:00 de hoje.
     *
     * Quem decide o desfecho é esta condição e mais nada. O `TaskRepository` arquiva por ela
     * (ver `saveDraft`); a tela avisa por ela antes de salvar. Escrita de novo na UI, a regra
     * voltaria a ter duas versões — que é o defeito de origem, em outra roupa.
     */
    fun bornWithoutReminder(
        firstOccurrenceAt: Instant,
        rule: RecurrenceRule,
        now: Instant,
    ): Boolean = firstOccurrenceAt.isBefore(now) && rule.kind == RecurrenceKind.NONE
}

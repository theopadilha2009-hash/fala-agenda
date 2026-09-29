package com.theopadilha.falaagenda.domain.reminder

import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
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
     * A data da primeira ocorrência de uma série que nasce com esta escolha.
     *
     * A data escolhida entra como **piso**, não como resposta: quem decide é a regra. Hoje é
     * terça, 29/09/2026, e ela fala "toda segunda natação às 18h" e toca no chip "Hoje": a
     * terça escolhida não satisfaz "toda segunda" e nunca vira ocorrência — nem no banco, nem
     * no `AlarmManager`. O primeiro aviso é segunda, 05/10/2026, e é essa data que a tela
     * precisa mostrar.
     *
     * O `?: chosenDate` é inalcançável hoje — `firstOnOrAfter` só devolve nulo para a regra que
     * não repete com `seriesStart` antes do piso, e aqui os dois argumentos são a mesma data. Fica
     * como rede: se o motor ganhar um caso nulo novo, a tela mostra a data escolhida em vez de
     * estourar no meio de um toque.
     */
    fun firstOccurrenceDate(rule: RecurrenceRule, chosenDate: LocalDate): LocalDate =
        RecurrenceEngine.firstOnOrAfter(rule, chosenDate, chosenDate) ?: chosenDate

    /** O instante do primeiro aviso: o mesmo que o alarme vai receber. */
    fun firstOccurrenceAt(
        rule: RecurrenceRule,
        chosenDate: LocalDate,
        chosenTime: LocalTime,
        zoneId: ZoneId,
    ): Instant = firstOccurrenceDate(rule, chosenDate).atTime(chosenTime).atZone(zoneId).toInstant()

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

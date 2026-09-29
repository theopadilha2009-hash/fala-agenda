package com.theopadilha.falaagenda.ui.home

import com.theopadilha.falaagenda.data.repo.AgendaItem

/**
 * Por que a ocorrência ficou para trás — e quem falhou.
 *
 * "Não realizadas" contava uma história só, e era sempre a mesma: ela deixou de fazer. Quando
 * nenhum aviso chegou a sair — os avisos estavam desligados, o canal bloqueado, a permissão
 * negada, ou a tarefa foi marcada para uma hora que já tinha passado —, quem falhou foi o
 * aplicativo, e era ela que a tela acusava, na frente do próprio remédio.
 *
 * O que separa os dois casos é o `lastReminderAt` da ocorrência: nulo é "nenhum aviso jamais
 * saiu", o mesmo campo que a varredura do ciclo de vida usa para decidir a expiração (ver
 * `OccurrenceLifecycle.expirou`). Com aviso entregue, a falta é dela.
 */
internal enum class MissedReason {
    /** O aviso tocou e ela não fez. Continua em "Não realizadas". */
    NOT_DONE,

    /** Nenhum aviso jamais saiu: o aplicativo não conseguiu avisar. */
    NOT_WARNED,
}

internal fun missedReason(item: AgendaItem): MissedReason =
    if (item.occurrence.lastReminderAt == null) MissedReason.NOT_WARNED else MissedReason.NOT_DONE

/**
 * Uma seção da home com o que ela lê.
 *
 * [note] entra na linha do cartão no lugar da recorrência: "08:00 · O aviso não tocou" conta
 * o que aconteceu; "08:00 · Só uma vez" não diz nada sobre o aviso que faltou. Nula quer
 * dizer "a linha fica como sempre foi".
 */
internal data class MissedSection(
    val title: String,
    val note: String? = null,
    val items: List<AgendaItem>,
)

/**
 * Em que seções a home abre o que ficou para trás, na ordem em que aparecem.
 *
 * A falha do aplicativo vem primeiro: é o que ela precisa saber antes de se achar culpada por
 * um remédio que ninguém avisou. Nenhuma ocorrência fica fora — cada uma tem exatamente um
 * dos dois motivos —, e seção vazia não aparece.
 */
internal fun missedSections(missed: List<AgendaItem>): List<MissedSection> = listOf(
    MissedSection(
        title = "Não consegui avisar",
        note = "O aviso não tocou.",
        items = missed.filter { missedReason(it) == MissedReason.NOT_WARNED },
    ),
    MissedSection(
        title = "Não realizadas",
        items = missed.filter { missedReason(it) == MissedReason.NOT_DONE },
    ),
).filter { it.items.isNotEmpty() }

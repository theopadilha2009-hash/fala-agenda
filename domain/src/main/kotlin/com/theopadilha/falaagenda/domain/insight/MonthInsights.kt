package com.theopadilha.falaagenda.domain.insight

import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * [naoAvisada] é a ocorrência que ficou para trás **sem nenhum aviso ter saído** — o aplicativo
 * não lembrou, ela não deixou de fazer. A home já separa os dois motivos (ver `missedReason`,
 * decidido por `lastReminderAt == null`); a camada de insights só consegue separar se este dado
 * viajar da ocorrência até aqui. Padrão `false` para a linha que não vem de uma não realizada.
 */
data class InsightRow(
    val title: String,
    val date: LocalDate,
    val status: OccurrenceStatus,
    val amountCents: Long? = null,
    val naoAvisada: Boolean = false,
)

data class TitleCount(
    val title: String,
    val times: Int,
)

/**
 * [missed] continua sendo o total de não realizadas — nada some da contagem. O que muda é a
 * atribuição: [naoRealizadas] é o que ficou para trás **com aviso entregue** (falta dela) e
 * [naoAvisadas] é o que o aplicativo não conseguiu avisar (falha do app). Os dois somam
 * [missed] sem sobreposição, e a leitura de fechamento de mês não cobra dela o que foi do app.
 */
data class MonthInsight(
    val yearMonth: YearMonth,
    val completed: Int,
    val missed: Int,
    val naoRealizadas: Int,
    val naoAvisadas: Int,
    val frequent: List<TitleCount>,
    val spentCents: Long,
) {
    fun monthLabel(locale: Locale = Locale.forLanguageTag("pt-BR")): String {
        val name = yearMonth.month.getDisplayName(TextStyle.FULL, locale)
        return "${name.replaceFirstChar { it.titlecase(locale) }} de ${yearMonth.year}"
    }

    /**
     * A falha do app com o app como sujeito, no mesmo vocabulário da home ("Não consegui
     * avisar"). Vazio quando não há falha: "Não consegui avisar 0 tarefas" seria ruído, e o
     * rótulo só entra na frase quando tem o que dizer.
     */
    fun naoAvisadasLabel(): String = when (naoAvisadas) {
        0 -> ""
        1 -> "Não consegui avisar 1 tarefa"
        else -> "Não consegui avisar $naoAvisadas tarefas"
    }

    fun spentLabel(locale: Locale = Locale.forLanguageTag("pt-BR")): String {
        if (spentCents <= 0) return ""
        val nf = NumberFormat.getCurrencyInstance(locale)
        return nf.format(spentCents / 100.0)
    }
}

object MonthInsights {
    fun of(rows: List<InsightRow>, month: YearMonth): MonthInsight {
        val inMonth = rows.filter { YearMonth.from(it.date) == month }
        val completed = inMonth.filter { it.status == OccurrenceStatus.COMPLETED }
        val missedRows = inMonth.filter { it.status == OccurrenceStatus.MISSED }
        // A separação vem do dado que a linha carrega, decidido na origem por `lastReminderAt`
        // — não de uma segunda conta de "o app avisou ou não" inventada aqui.
        val naoAvisadas = missedRows.count { it.naoAvisada }
        // "O que mais você fez" só conta o que foi concluído: não realizada ou ainda pendente não é feito.
        val frequent = completed
            .groupBy { it.title.trim().lowercase() }
            .map { (_, group) -> TitleCount(group.first().title.trim(), group.size) }
            .sortedWith(compareByDescending<TitleCount> { it.times }.thenBy { it.title.lowercase() })
            .take(8)
        val spent = completed.mapNotNull { it.amountCents }.sum()
        return MonthInsight(
            yearMonth = month,
            completed = completed.size,
            missed = missedRows.size,
            naoRealizadas = missedRows.size - naoAvisadas,
            naoAvisadas = naoAvisadas,
            frequent = frequent,
            spentCents = spent,
        )
    }
}

object Money {
    fun parseReais(text: String): Long? {
        val trimmed = text.trim()
            .replace("R$", "", ignoreCase = true)
            .replace(" ", "")
        if (trimmed.isBlank()) return null
        val normalized = when {
            trimmed.contains(',') && trimmed.contains('.') -> {
                if (trimmed.lastIndexOf(',') > trimmed.lastIndexOf('.')) {
                    trimmed.replace(".", "").replace(",", ".")
                } else {
                    trimmed.replace(",", "")
                }
            }
            trimmed.contains(',') -> trimmed.replace(".", "").replace(",", ".")
            trimmed.contains('.') -> {
                val parts = trimmed.split('.')
                if (parts.size == 2 && parts[1].length in 1..2) trimmed else trimmed.replace(".", "")
            }
            else -> trimmed
        }
        val value = normalized.toDoubleOrNull() ?: return null
        if (value < 0) return null
        return Math.round(value * 100.0)
    }

    fun formatReais(cents: Long, locale: Locale = Locale.forLanguageTag("pt-BR")): String {
        val nf = NumberFormat.getCurrencyInstance(locale)
        return nf.format(cents / 100.0)
    }
}

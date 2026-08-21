package com.theopadilha.falaagenda.domain.insight

import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

data class InsightRow(
    val title: String,
    val date: LocalDate,
    val status: OccurrenceStatus,
    val amountCents: Long? = null,
)

data class TitleCount(
    val title: String,
    val times: Int,
)

data class MonthInsight(
    val yearMonth: YearMonth,
    val completed: Int,
    val missed: Int,
    val frequent: List<TitleCount>,
    val spentCents: Long,
) {
    fun monthLabel(locale: Locale = Locale.forLanguageTag("pt-BR")): String {
        val name = yearMonth.month.getDisplayName(TextStyle.FULL, locale)
        return "${name.replaceFirstChar { it.titlecase(locale) }} de ${yearMonth.year}"
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
        val missed = inMonth.count { it.status == OccurrenceStatus.MISSED }
        val frequent = completed
            .groupBy { it.title.trim().lowercase() }
            .map { (_, group) -> TitleCount(group.first().title.trim(), group.size) }
            .sortedWith(compareByDescending<TitleCount> { it.times }.thenBy { it.title.lowercase() })
            .take(8)
        val spent = completed.mapNotNull { it.amountCents }.sum()
        return MonthInsight(
            yearMonth = month,
            completed = completed.size,
            missed = missed,
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

package com.theopadilha.falaagenda.domain.recurrence

import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

object RecurrenceEngine {
    /**
     * Ajusta dia 29/30/31 ao último dia válido do mês.
     * 29 de fevereiro em ano não bissexto cai em 28 de fevereiro.
     */
    fun clampToValidDate(year: Int, month: Int, dayOfMonth: Int): LocalDate {
        val ym = YearMonth.of(year, month)
        val day = dayOfMonth.coerceIn(1, ym.lengthOfMonth())
        return ym.atDay(day)
    }

    fun yearlyDate(year: Int, month: Int, dayOfMonth: Int): LocalDate {
        if (month == 2 && dayOfMonth == 29) {
            return if (YearMonth.of(year, 2).isLeapYear) {
                LocalDate.of(year, 2, 29)
            } else {
                LocalDate.of(year, 2, 28)
            }
        }
        return clampToValidDate(year, month, dayOfMonth)
    }

    /**
     * Primeira ocorrência em [onOrAfter] (inclusive) que respeita a regra,
     * nunca antes de [seriesStart].
     */
    fun firstOnOrAfter(
        rule: RecurrenceRule,
        seriesStart: LocalDate,
        onOrAfter: LocalDate,
    ): LocalDate? {
        val from = if (onOrAfter.isBefore(seriesStart)) seriesStart else onOrAfter
        return when (rule.kind) {
            RecurrenceKind.NONE -> if (!seriesStart.isBefore(onOrAfter)) seriesStart else null
            RecurrenceKind.DAILY -> from
            RecurrenceKind.WEEKDAYS -> nextWeekDay(from, WEEKDAYS)
            RecurrenceKind.WEEKLY -> {
                val days = rule.weekDays.ifEmpty { setOf(seriesStart.dayOfWeek) }
                nextWeekDay(from, days)
            }
            RecurrenceKind.MONTHLY -> {
                val desired = rule.dayOfMonth ?: seriesStart.dayOfMonth
                nextMonthly(from, desired)
            }
            RecurrenceKind.YEARLY -> {
                val month = rule.monthOfYear ?: seriesStart.monthValue
                val day = rule.dayOfMonth ?: seriesStart.dayOfMonth
                nextYearly(from, month, day)
            }
        }
    }

    fun nextAfter(
        rule: RecurrenceRule,
        seriesStart: LocalDate,
        after: LocalDate,
    ): LocalDate? {
        if (rule.kind == RecurrenceKind.NONE) return null
        return firstOnOrAfter(rule, seriesStart, after.plusDays(1))
    }

    fun upcoming(
        rule: RecurrenceRule,
        seriesStart: LocalDate,
        from: LocalDate,
        limit: Int,
    ): List<LocalDate> {
        if (limit <= 0) return emptyList()
        val first = firstOnOrAfter(rule, seriesStart, from) ?: return emptyList()
        if (!rule.isRecurring) return listOf(first)
        val out = ArrayList<LocalDate>(limit)
        var current: LocalDate? = first
        repeat(limit) {
            val value = current ?: return@repeat
            out += value
            current = nextAfter(rule, seriesStart, value)
        }
        return out
    }

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    private fun nextWeekDay(from: LocalDate, days: Set<DayOfWeek>): LocalDate {
        var cursor = from
        repeat(8) {
            if (cursor.dayOfWeek in days) return cursor
            cursor = cursor.plusDays(1)
        }
        return from
    }

    private fun nextMonthly(from: LocalDate, desiredDay: Int): LocalDate {
        var year = from.year
        var month = from.monthValue
        repeat(14) {
            val candidate = clampToValidDate(year, month, desiredDay)
            if (!candidate.isBefore(from)) return candidate
            if (month == 12) {
                month = 1
                year += 1
            } else {
                month += 1
            }
        }
        throw DateTimeException("Não foi possível calcular ocorrência mensal")
    }

    private fun nextYearly(from: LocalDate, month: Int, day: Int): LocalDate {
        var year = from.year
        repeat(3) {
            val candidate = yearlyDate(year, month, day)
            if (!candidate.isBefore(from)) return candidate
            year += 1
        }
        throw DateTimeException("Não foi possível calcular ocorrência anual")
    }
}

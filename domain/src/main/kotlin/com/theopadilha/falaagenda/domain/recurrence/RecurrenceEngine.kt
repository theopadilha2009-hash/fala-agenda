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
     *
     * Mês fora de `1..12` devolve **nulo**, não estoura. A faixa inválida não é erro de
     * programação: é dado que entrou de fora. O schema do LLM
     * (`supabase/functions/_shared/openai.ts`) declara `month_of_year` como `integer` sem
     * `minimum`/`maximum`, e o modelo devolve `0`, `13` ou negativo. O `YearMonth.of` estourava
     * `DateTimeException`, e quem chamava era `DraftSchedule.firstOccurrence` **na composição** da
     * caixa de confirmação rápida: a home caía, sem error boundary e num caminho em que a tela de
     * confirmação nem aparece. Nulo aqui é o mesmo contrato de campo ausente do resto do domínio
     * (`dayOfMonth`/`monthOfYear` nulos já significam "não foi dito"), e quem chama decide o que
     * fazer: [firstOnOrAfter] trata como não informado, e `DraftSchedule` cai na data escolhida.
     */
    fun clampToValidDate(year: Int, month: Int, dayOfMonth: Int): LocalDate? {
        if (month !in 1..12) return null
        val ym = YearMonth.of(year, month)
        val day = dayOfMonth.coerceIn(1, ym.lengthOfMonth())
        return ym.atDay(day)
    }

    /**
     * O dia existe neste mês? `31 de abril` não existe, e `29 de fevereiro` só existe no bissexto
     * — a pergunta que `clampToValidDate` responde arredondando e que o parser precisa responder
     * recusando, quando o dia foi dito e o mês deduzido. O 29 de fevereiro fica de fora da recusa:
     * a série anual cai no bissexto seguinte, e o dia existe em algum ano.
     */
    fun dayExistsInMonth(dayOfMonth: Int, month: Int): Boolean {
        if (month !in 1..12) return false
        if (dayOfMonth !in 1..31) return false
        if (dayOfMonth == 29 && month == 2) return true
        return dayOfMonth <= YearMonth.of(2001, month).lengthOfMonth()
    }

    /**
     * A data da regra anual. Nula quando o mês não existe — a faixa inválida vem de fora e não
     * pode virar exceção no meio de um toque; ver [clampToValidDate] para o porquê.
     */
    fun yearlyDate(year: Int, month: Int, dayOfMonth: Int): LocalDate? {
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
                // Mês fora da faixa é dado ruim de fora (a IA devolve `13`), não um pedido de
                // "todo dia 5 do mês 13". Tratado como ausente, cai no mês da própria série —
                // o mesmo destino do campo nulo, em vez do `DateTimeException` que derrubava a
                // home. Ver [clampToValidDate].
                val month = rule.monthOfYear?.takeIf { it in 1..12 } ?: seriesStart.monthValue
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
            if (candidate != null && !candidate.isBefore(from)) return candidate
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
            if (candidate != null && !candidate.isBefore(from)) return candidate
            year += 1
        }
        throw DateTimeException("Não foi possível calcular ocorrência anual")
    }
}

package com.theopadilha.falaagenda.ui

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object AgendaFormat {
    private val locale: Locale = Locale.forLanguageTag("pt-BR")
    private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", locale)
    private val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' uuuu", locale)
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)

    fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Hoje"
        today.plusDays(1) -> "Amanhã"
        today.minusDays(1) -> "Ontem"
        else -> date.format(dayMonth)
    }

    fun longDate(date: LocalDate): String = date.format(longDate).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(locale) else it.toString()
    }

    fun time(time: LocalTime): String = time.format(clock)
}

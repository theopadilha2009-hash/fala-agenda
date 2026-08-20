package com.theopadilha.falaagenda.ui

import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import java.time.Duration
import java.time.Instant
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

    fun greeting(time: LocalTime): String = when (time.hour) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }

    fun announce(date: LocalDate, time: LocalTime, today: LocalDate): String {
        val whenLabel = dateLabel(date, today).lowercase(locale)
        return "Vai avisar $whenLabel às ${time(time)}."
    }

    fun recap(date: LocalDate, time: LocalTime, recurrence: RecurrenceRule): String =
        "Vai avisar ${longDate(date)} às ${time(time)}. ${recurrence.describePtBr()}."

    fun headline(
        nowTime: LocalTime,
        today: LocalDate,
        nextTitle: String?,
        nextDate: LocalDate?,
        nextTime: LocalTime?,
        missedCount: Int,
    ): String {
        val greet = greeting(nowTime)
        val next = if (nextTitle != null && nextDate != null && nextTime != null) {
            val whenLabel = dateLabel(nextDate, today).lowercase(locale)
            " Próximo: $nextTitle, $whenLabel às ${time(nextTime)}."
        } else {
            " Nada marcado agora."
        }
        val missed = when (missedCount) {
            0 -> ""
            1 -> " 1 recado ficou para trás."
            else -> " $missedCount recados ficaram para trás."
        }
        return "$greet.$next$missed"
    }

    fun fromNow(target: Instant, now: Instant): String? {
        val minutes = Duration.between(now, target).toMinutes()
        return when {
            minutes in -1L..1L -> "agora"
            minutes in 2L..59L -> "daqui $minutes min"
            minutes in 60L..(24L * 60L - 1L) -> {
                val hours = minutes / 60
                if (minutes % 60 == 0L) "daqui $hours h" else "daqui ${hours} h"
            }
            minutes in -59L..-2L -> "há ${-minutes} min"
            minutes in -(24L * 60L - 1L)..-60L -> "há ${-minutes / 60} h"
            else -> null
        }
    }
}

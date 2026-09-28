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

    data class DayShareLine(
        val title: String,
        val time: LocalTime,
        val observation: String = "",
        /** "ontem", "25/08": de que dia é a tarefa, quando não é de hoje. Ver [shareDayMark]. */
        val dayMark: String? = null,
    )

    /**
     * De que dia é a tarefa para quem lê de fora: nulo quando é de hoje, porque aí o
     * cabeçalho já diz o dia. A seção "Hoje" da agenda recebe a pendente que atravessou a
     * meia-noite, então sem esta marca a tarefa de ontem saía no compartilhado como se
     * fosse de hoje.
     */
    fun shareDayMark(date: LocalDate, today: LocalDate): String? =
        if (date == today) null else dateLabel(date, today).lowercase(locale)

    /**
     * A pendente que atravessou a meia-noite entra na seção "Hoje" para continuar ao
     * alcance dela: sem esta marca a linha de ontem ficava igual à de hoje embaixo do mesmo
     * cabeçalho. Onde não é atrasada a resposta é nula, e a linha mostra o de sempre.
     */
    fun lateMark(date: LocalDate, today: LocalDate): String? =
        if (date.isBefore(today)) "atrasada" else null

    fun todayShare(lines: List<DayShareLine>): String {
        if (lines.isEmpty()) return "Hoje no Fala Agenda não tem nada marcado."
        val body = lines.joinToString("\n") { line ->
            val extra = line.observation.trim().takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
            val whenDay = line.dayMark?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
            "• ${line.title}$whenDay às ${time(line.time)}$extra"
        }
        return "Hoje no Fala Agenda:\n$body"
    }

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

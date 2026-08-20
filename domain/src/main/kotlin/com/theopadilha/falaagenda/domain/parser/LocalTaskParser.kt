package com.theopadilha.falaagenda.domain.parser

import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import com.theopadilha.falaagenda.domain.time.AppClock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

/**
 * Parser determinístico pt-BR. Nunca inventa data/hora ausente.
 */
class LocalTaskParser(
    private val clock: AppClock,
    private val locale: Locale = Locale.forLanguageTag("pt-BR"),
) {
    fun parse(transcript: String): ParsedTaskDraft {
        val original = transcript.trim()
        if (original.isBlank()) {
            return ParsedTaskDraft(
                title = "",
                localDate = null,
                localTime = null,
                confidence = 0.0,
                missingFields = setOf(
                    MissingDraftField.TITLE,
                    MissingDraftField.DATE,
                    MissingDraftField.TIME,
                ),
                ambiguous = false,
                transcript = original,
                notes = listOf("Nada foi dito."),
                source = DraftSource.LOCAL,
            )
        }

        val folded = TextNormalizer.fold(original)
        val working = TextNormalizer.compactSpaces(folded)
        val notes = mutableListOf<String>()
        var remaining = working
        var ambiguous = false
        var confidence = 0.85

        val recurrenceHit = extractRecurrence(remaining)
        remaining = recurrenceHit.remaining
        val recurrence = recurrenceHit.rule
        if (recurrenceHit.ambiguous) {
            ambiguous = true
            confidence = minOf(confidence, 0.45)
            notes += "A recorrência ficou ambígua."
        }

        val timeHit = extractTime(remaining)
        remaining = timeHit.remaining
        val localTime = timeHit.time
        if (timeHit.ambiguous) {
            ambiguous = true
            confidence = minOf(confidence, 0.5)
            notes += "O horário ficou ambíguo."
        }

        val dateHit = extractDate(remaining, recurrence)
        remaining = dateHit.remaining
        var localDate = dateHit.date
        if (dateHit.ambiguous) {
            ambiguous = true
            confidence = minOf(confidence, 0.5)
            notes += "A data ficou ambígua."
        }

        if (localDate == null && recurrence.isRecurring) {
            val today = clock.today()
            localDate = RecurrenceEngine.firstOnOrAfter(recurrence, today, today)
        }

        val title = extractTitle(remaining, original)
        val missing = buildSet {
            if (title.isBlank()) add(MissingDraftField.TITLE)
            if (localDate == null) add(MissingDraftField.DATE)
            if (localTime == null) add(MissingDraftField.TIME)
        }
        if (missing.isNotEmpty()) {
            confidence = minOf(confidence, 0.55)
        }
        if (localDate != null && localTime != null) {
            val scheduled = localDate.atTime(localTime).atZone(clock.zoneId()).toInstant()
            if (scheduled.isBefore(clock.instant()) && !recurrence.isRecurring) {
                notes += "Essa data e horário já passaram."
            }
        }
        if (missing.contains(MissingDraftField.DATE)) {
            notes += "Falta a data. Não inventamos um dia."
        }
        if (missing.contains(MissingDraftField.TIME)) {
            notes += "Falta o horário. Não inventamos uma hora."
        }

        return ParsedTaskDraft(
            title = title,
            localDate = localDate,
            localTime = localTime,
            recurrence = recurrence,
            confidence = confidence,
            missingFields = missing,
            ambiguous = ambiguous,
            transcript = original,
            notes = notes,
            source = DraftSource.LOCAL,
        )
    }

    private data class RecurrenceHit(
        val rule: RecurrenceRule,
        val remaining: String,
        val ambiguous: Boolean,
    )

    private fun extractRecurrence(text: String): RecurrenceHit {
        var remaining = text
        var ambiguous = false

        val yearlyExtenso = Regex(
            """\bto[doas]+\s+(?:dia\s+)?(\d{1,2})\s+de\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\b""",
        )
        yearlyExtenso.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = monthFromName(m.groupValues[2])
            remaining = remaining.replace(m.value, " ")
            return RecurrenceHit(
                RecurrenceRule(RecurrenceKind.YEARLY, dayOfMonth = day, monthOfYear = month),
                remaining,
                false,
            )
        }

        val monthly = Regex("""\bto[doas]+\s+dia\s+(\d{1,2})(?:\s+do\s+mes)?\b""")
        monthly.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            remaining = remaining.replace(m.value, " ")
            return RecurrenceHit(
                RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = day),
                remaining,
                day !in 1..31,
            )
        }

        val weekdaysPhrase = Regex("""\b(?:em\s+)?dias\s+uteis\b""")
        if (weekdaysPhrase.containsMatchIn(remaining)) {
            remaining = remaining.replace(weekdaysPhrase, " ")
            return RecurrenceHit(RecurrenceRule(RecurrenceKind.WEEKDAYS), remaining, false)
        }

        val daily = Regex("""\b(?:todos?\s+os\s+dias|todo\s+dia|diariamente)\b""")
        if (daily.containsMatchIn(remaining)) {
            remaining = remaining.replace(daily, " ")
            return RecurrenceHit(RecurrenceRule(RecurrenceKind.DAILY), remaining, false)
        }

        val weeklyPrefix = Regex("""\b(?:todas?\s+as?|todos?\s+os)\s+""")
        val weekly = weeklyPrefix.find(remaining)
        if (weekly != null) {
            val after = remaining.substring(weekly.range.last + 1)
            val days = extractWeekDays(after)
            if (days.isNotEmpty()) {
                remaining = remaining.replaceRange(weekly.range.first, remaining.length, stripWeekDays(after))
                remaining = TextNormalizer.compactSpaces(remaining)
                if (days.size > 1) {
                    // múltiplos dias semanais é suportado e não é ambíguo
                }
                return RecurrenceHit(RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days), remaining, false)
            }
            ambiguous = true
        }

        val toda = Regex("""\btoda\s+""")
        toda.find(remaining)?.let { m ->
            val after = remaining.substring(m.range.last + 1)
            val days = extractWeekDays(after)
            if (days.isNotEmpty()) {
                remaining = remaining.replaceRange(m.range.first, remaining.length, stripWeekDays(after))
                remaining = TextNormalizer.compactSpaces(remaining)
                return RecurrenceHit(RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days), remaining, false)
            }
        }

        return RecurrenceHit(RecurrenceRule(), remaining, ambiguous)
    }

    private data class TimeHit(val time: LocalTime?, val remaining: String, val ambiguous: Boolean)

    private fun extractTime(text: String): TimeHit {
        var remaining = text
        Regex("""\bmeio[-\s]?dia\b""").find(remaining)?.let {
            remaining = remaining.replace(it.value, " ")
            return TimeHit(LocalTime.NOON, remaining, false)
        }
        Regex("""\bmeia[-\s]?noite\b""").find(remaining)?.let {
            remaining = remaining.replace(it.value, " ")
            return TimeHit(LocalTime.MIDNIGHT, remaining, false)
        }

        val clock = Regex(
            """\b(?:as\s+)?(\d{1,2})(?:[:h](\d{2})|\s*h(?:oras?)?(?:\s*(\d{2}))?)(?:\s*(da\s+manha|da\s+tarde|da\s+noite|da\s+madrugada))?\b""",
        )
        val matches = clock.findAll(remaining).toList()
        if (matches.size > 1) {
            return TimeHit(null, remaining, true)
        }
        val m = matches.firstOrNull() ?: return TimeHit(null, remaining, false)
        val hourRaw = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifBlank { m.groupValues[3] }.ifBlank { "0" }.toInt()
        val period = m.groupValues[4]
        if (hourRaw !in 0..23 || minute !in 0..59) {
            return TimeHit(null, remaining.replace(m.value, " "), true)
        }
        val hour = when {
            period.contains("tarde") && hourRaw in 1..11 -> hourRaw + 12
            period.contains("noite") && hourRaw in 1..11 -> hourRaw + 12
            period.contains("manha") && hourRaw == 12 -> 0
            period.contains("madrugada") && hourRaw == 12 -> 0
            else -> hourRaw
        }
        remaining = remaining.replace(m.value, " ")
        return TimeHit(LocalTime.of(hour % 24, minute), remaining, false)
    }

    private data class DateHit(val date: LocalDate?, val remaining: String, val ambiguous: Boolean)

    private fun extractDate(text: String, recurrence: RecurrenceRule): DateHit {
        var remaining = text
        val today = clock.today()

        Regex("""\bdepois\s+de\s+amanha\b""").find(remaining)?.let {
            remaining = remaining.replace(it.value, " ")
            return DateHit(today.plusDays(2), remaining, false)
        }
        Regex("""\bamanha\b""").find(remaining)?.let {
            remaining = remaining.replace(it.value, " ")
            return DateHit(today.plusDays(1), remaining, false)
        }
        Regex("""\bhoje\b""").find(remaining)?.let {
            remaining = remaining.replace(it.value, " ")
            return DateHit(today, remaining, false)
        }

        val numeric = Regex("""\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""")
        numeric.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearRaw = m.groupValues[3]
            val year = when {
                yearRaw.isBlank() -> inferYear(today, month, day)
                yearRaw.length == 2 -> 2000 + yearRaw.toInt()
                else -> yearRaw.toInt()
            }
            remaining = remaining.replace(m.value, " ")
            if (month !in 1..12 || day !in 1..31) {
                return DateHit(null, remaining, true)
            }
            val date = RecurrenceEngine.clampToValidDate(year, month, day)
            return DateHit(date, remaining, false)
        }

        val extenso = Regex(
            """\b(\d{1,2})\s+de\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)(?:\s+de\s+(\d{4}))?\b""",
        )
        extenso.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = monthFromName(m.groupValues[2])
            val year = m.groupValues[3].ifBlank { inferYear(today, month, day).toString() }.toInt()
            remaining = remaining.replace(m.value, " ")
            val date = RecurrenceEngine.clampToValidDate(year, month, day)
            return DateHit(date, remaining, false)
        }

        if (!recurrence.isRecurring) {
            val days = extractWeekDays(remaining)
            if (days.size == 1) {
                remaining = stripWeekDays(remaining)
                val date = RecurrenceEngine.firstOnOrAfter(
                    RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days),
                    today,
                    today,
                )
                return DateHit(date, remaining, false)
            }
            if (days.size > 1) {
                return DateHit(null, remaining, true)
            }
        }

        return DateHit(null, remaining, false)
    }

    private fun inferYear(today: LocalDate, month: Int, day: Int): Int {
        val candidate = try {
            YearMonth.of(today.year, month).atDay(day.coerceAtMost(YearMonth.of(today.year, month).lengthOfMonth()))
        } catch (_: Exception) {
            today
        }
        return if (candidate.isBefore(today)) today.year + 1 else today.year
    }

    private fun extractTitle(remaining: String, original: String): String {
        val fillers = setOf(
            "me", "lembrar", "lembre", "lembra", "de", "que", "pra", "para", "o", "a", "os", "as",
            "um", "uma", "do", "da", "dos", "das", "no", "na", "em", "ao", "aos",
            "lembrete", "agendar", "agenda", "por", "favor", "preciso", "tenho",
            "marcar", "anota", "anotar", "tarefa", "compromisso", "e", "eh",
        )
        val leftover = TextNormalizer.compactSpaces(remaining)
            .split(" ")
            .filter { it.isNotBlank() && it !in fillers && !it.matches(Regex("\\d+h?")) }
            .toMutableList()
        val rebuilt = original.split(Regex("\\s+")).filter { word ->
            val folded = TextNormalizer.fold(word).trim(',', '.', '!', '?')
            val idx = leftover.indexOfFirst { it == folded || folded.startsWith(it) }
            if (idx >= 0) {
                leftover.removeAt(idx)
                true
            } else {
                false
            }
        }
        val title = rebuilt.joinToString(" ").trim().trim(',', '.', '!')
        if (title.isNotBlank()) {
            return title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
        return ""
    }

    private fun extractWeekDays(text: String): Set<DayOfWeek> {
        val found = linkedSetOf<DayOfWeek>()
        WEEKDAY_PATTERNS.forEach { (regex, day) ->
            if (regex.containsMatchIn(text)) found += day
        }
        return found
    }

    private fun stripWeekDays(text: String): String {
        var remaining = text
        WEEKDAY_PATTERNS.forEach { (regex, _) ->
            remaining = remaining.replace(regex, " ")
        }
        remaining = remaining.replace(Regex("""\b(e|,)\b"""), " ")
        remaining = remaining.replace(Regex("""\bfeiras?\b"""), " ")
        return TextNormalizer.compactSpaces(remaining)
    }

    private fun monthFromName(name: String): Int = when (name) {
        "janeiro" -> 1
        "fevereiro" -> 2
        "marco" -> 3
        "abril" -> 4
        "maio" -> 5
        "junho" -> 6
        "julho" -> 7
        "agosto" -> 8
        "setembro" -> 9
        "outubro" -> 10
        "novembro" -> 11
        "dezembro" -> 12
        else -> 1
    }

    companion object {
        private val WEEKDAY_PATTERNS = listOf(
            Regex("""\bdomingos?(?:-?feira)?\b""") to DayOfWeek.SUNDAY,
            Regex("""\bsegundas?(?:-?feira)?\b""") to DayOfWeek.MONDAY,
            Regex("""\btercas?(?:-?feira)?\b""") to DayOfWeek.TUESDAY,
            Regex("""\bquartas?(?:-?feira)?\b""") to DayOfWeek.WEDNESDAY,
            Regex("""\bquintas?(?:-?feira)?\b""") to DayOfWeek.THURSDAY,
            Regex("""\bsextas?(?:-?feira)?\b""") to DayOfWeek.FRIDAY,
            Regex("""\bsabados?(?:-?feira)?\b""") to DayOfWeek.SATURDAY,
        )
    }
}

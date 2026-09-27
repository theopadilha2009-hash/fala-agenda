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
import java.time.temporal.WeekFields
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
        var localTime = timeHit.time
        if (timeHit.ambiguous) {
            ambiguous = true
            confidence = minOf(confidence, 0.5)
            notes += timeHit.note ?: "O horário ficou ambíguo."
        }

        val dateHit = extractDate(remaining, recurrence)
        remaining = dateHit.remaining
        var localDate = dateHit.date
        if (dateHit.ambiguous) {
            ambiguous = true
            confidence = minOf(confidence, 0.5)
            notes += "A data ficou ambígua."
        }

        val periodHit = extractPeriodHint(remaining)
        remaining = periodHit.remaining
        if (periodHit.hint != null) {
            if (localTime != null && localTime.hour in 1..11) {
                localTime = applyPeriod(localTime, periodHit.hint)
            } else if (localTime == null) {
                ambiguous = true
                confidence = minOf(confidence, 0.5)
                notes += "“${periodHit.label}” não é um horário exato. Complete o horário — não inventamos."
            }
        }

        if (localDate == null && recurrence.isRecurring) {
            val today = clock.today()
            val time = localTime
            var next = RecurrenceEngine.firstOnOrAfter(recurrence, today, today)
            if (recurrenceHit.nextWeek && next != null) next = nextWeekOf(next, today)
            if (time != null && next == today) {
                val scheduled = today.atTime(time).atZone(clock.zoneId()).toInstant()
                if (scheduled.isBefore(clock.instant())) {
                    next = RecurrenceEngine.firstOnOrAfter(recurrence, today, today.plusDays(1))
                }
            }
            localDate = next
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
        /** "toda quinta que vem": a série começa na ocorrência da próxima semana. */
        val nextWeek: Boolean = false,
    )

    private fun extractRecurrence(text: String): RecurrenceHit {
        var remaining = text
        var ambiguous = false

        val yearlyAno = Regex(
            """\bto[doas]+\s+ano\s+(?:(?:no\s+)?dia\s+)?(\d{1,2})\s+de\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\b""",
        )
        yearlyAno.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = monthFromName(m.groupValues[2])
            remaining = remaining.replace(m.value, " ")
            return RecurrenceHit(
                RecurrenceRule(RecurrenceKind.YEARLY, dayOfMonth = day, monthOfYear = month),
                remaining,
                day !in 1..31,
            )
        }

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

        val monthlyCada = Regex("""\bdia\s+(\d{1,2})\s+de\s+cada\s+mes\b""")
        monthlyCada.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            remaining = remaining.replace(m.value, " ")
            return RecurrenceHit(
                RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = day),
                remaining,
                day !in 1..31,
            )
        }

        val monthlyTodoMes = Regex("""\btodo\s+mes\s+(?:(?:no\s+)?dia\s+)?(\d{1,2})\b""")
        monthlyTodoMes.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            remaining = remaining.replace(m.value, " ")
            return RecurrenceHit(
                RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = day),
                remaining,
                day !in 1..31,
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

        val weekdaysPhrase =
            Regex("""\b(?:(?:em|nos|nas|todos?|todas?)\s+)?(?:os\s+|as\s+)?dias?\s+(?:uteis|util)\b""")
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
                val nextWeek = WEEKDAY_NEXT_WEEK.containsMatchIn(after)
                remaining = remaining.replaceRange(weekly.range.first, remaining.length, stripWeekDays(after))
                remaining = TextNormalizer.compactSpaces(remaining)
                return RecurrenceHit(
                    RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days),
                    remaining,
                    false,
                    nextWeek,
                )
            }
            ambiguous = true
        }

        val toda = Regex("""\btoda\s+""")
        toda.find(remaining)?.let { m ->
            val after = remaining.substring(m.range.last + 1)
            val days = extractWeekDays(after)
            if (days.isNotEmpty()) {
                val nextWeek = WEEKDAY_NEXT_WEEK.containsMatchIn(after)
                remaining = remaining.replaceRange(m.range.first, remaining.length, stripWeekDays(after))
                remaining = TextNormalizer.compactSpaces(remaining)
                return RecurrenceHit(
                    RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days),
                    remaining,
                    false,
                    nextWeek,
                )
            }
        }

        return RecurrenceHit(RecurrenceRule(), remaining, ambiguous)
    }

    private data class TimeHit(
        val time: LocalTime?,
        val remaining: String,
        val ambiguous: Boolean,
        val note: String? = null,
    )

    private fun extractTime(text: String): TimeHit {
        var remaining = text

        // "de 8 em 8 horas", "a cada 2 horas": intervalo entre doses, não um horário do dia.
        INTERVAL.find(remaining)?.let { m ->
            remaining = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
            return TimeHit(
                null,
                remaining,
                true,
                "“${m.value}” é um intervalo, não um horário do dia. Diga o horário da primeira dose.",
            )
        }

        val relative = extractRelative(remaining)
        if (relative != null) {
            val marker = if (relative.date == clock.today()) " hoje " else " amanha "
            return TimeHit(
                relative.time,
                TextNormalizer.compactSpaces(marker + relative.remaining),
                false,
            )
        }

        Regex("""\bmeio[-\s]?dia\b""").find(remaining)?.let { m ->
            val tail = trailingMinutes(remaining, m.range.last + 1)
            remaining = remaining.replace(m.value + (tail?.second ?: ""), " ")
            return TimeHit(LocalTime.of(12, tail?.first ?: 0), remaining, false)
        }
        Regex("""\bmeia[-\s]?noite\b""").find(remaining)?.let { m ->
            val tail = trailingMinutes(remaining, m.range.last + 1)
            remaining = remaining.replace(m.value + (tail?.second ?: ""), " ")
            return TimeHit(LocalTime.of(0, tail?.first ?: 0), remaining, false)
        }

        data class ClockMatch(val match: MatchResult, val hourRaw: Int, val minute: Int, val period: String)

        val found = mutableListOf<ClockMatch>()
        CLOCK_NUMERIC.findAll(remaining).forEach { m ->
            val hourRaw = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifBlank { m.groupValues[3] }.ifBlank { "0" }.toInt()
            found += ClockMatch(m, hourRaw, minute, m.groupValues[4])
        }
        CLOCK_WORD.findAll(remaining).forEach { m ->
            val hourRaw = WORD_HOURS[m.groupValues[1]] ?: return@forEach
            val minute = m.groupValues[2].ifBlank { "0" }.toInt()
            found += ClockMatch(m, hourRaw, minute, m.groupValues[3])
        }
        CLOCK_BARE.findAll(remaining).forEach { m ->
            if (found.any { it.match.range.first == m.range.first }) return@forEach
            found += ClockMatch(m, m.groupValues[1].toInt(), 0, m.groupValues[2])
        }
        if (found.size > 1) {
            return TimeHit(null, remaining, true)
        }
        val hit = found.firstOrNull() ?: return TimeHit(null, remaining, false)
        if (hit.hourRaw !in 0..23 || hit.minute !in 0..59) {
            return TimeHit(null, remaining.replace(hit.match.value, " "), true)
        }
        var minute = hit.minute
        var consumed = hit.match.value
        trailingMinutes(remaining, hit.match.range.last + 1)?.let { tail ->
            if (minute + tail.first <= 59) {
                minute += tail.first
                consumed += tail.second
            }
        }
        val hour = applyPeriodHour(hit.hourRaw, hit.period)
        remaining = remaining.replace(consumed, " ")
        return TimeHit(LocalTime.of(hour % 24, minute), remaining, false)
    }

    /**
     * "às 9 e meia", "às nove e vinte e cinco": o "e <minutos>" colado no relógio vira o minuto.
     * Dezena por extenso aceita a unidade: "quarenta e cinco" são 45, não 40 + "cinco" solto no título.
     */
    private fun trailingMinutes(text: String, from: Int): Pair<Int, String>? {
        val m = MINUTE_TAIL.find(text, from) ?: return null
        if (m.range.first != from) return null
        val raw = m.groupValues[1]
        val base = raw.toIntOrNull() ?: MINUTE_TAIL_WORDS[raw] ?: return null
        val unit = m.groupValues[2]
        val extra = if (unit.isBlank()) {
            base
        } else {
            if (raw !in MINUTE_TENS) return null
            base + (MINUTE_UNITS[unit] ?: return null)
        }
        return if (extra in 0..59) extra to m.value else null
    }

    private data class RelativeHit(val time: LocalTime, val date: LocalDate, val remaining: String)

    private fun extractRelative(text: String): RelativeHit? {
        val meiaHora = Regex("""\b(?:daqui(?:\s+a)?|em)\s+meia\s+hora\b""")
        meiaHora.find(text)?.let { m ->
            val target = clock.now().plusMinutes(30)
            return RelativeHit(
                time = target.toLocalTime().withSecond(0).withNano(0),
                date = target.toLocalDate(),
                remaining = text.replace(m.value, " "),
            )
        }
        val amount = Regex(
            """\b(?:daqui(?:\s+a)?|em)\s+(\d+|[a-z]+(?:\s+e\s+[a-z]+)?)\s+(min\.?|minutos?|horas?)(?!\w)""",
        )
        amount.find(text)?.let { m ->
            val raw = TextNormalizer.compactSpaces(m.groupValues[1])
            val unit = m.groupValues[2]
            val n = raw.toIntOrNull() ?: WORD_AMOUNTS[raw] ?: return null
            var consumed = m.value
            var extraMinutes = 0L
            if (unit.startsWith("hora")) {
                val meia = MEIA_HORA_TAIL.find(text, m.range.last + 1)
                if (meia != null && meia.range.first == m.range.last + 1) {
                    extraMinutes = 30
                    consumed += meia.value
                }
            }
            val target = if (unit.startsWith("hora")) {
                clock.now().plusHours(n.toLong())
            } else {
                clock.now().plusMinutes(n.toLong())
            }.plusMinutes(extraMinutes)
            return RelativeHit(
                time = target.toLocalTime().withSecond(0).withNano(0),
                date = target.toLocalDate(),
                remaining = text.replace(consumed, " "),
            )
        }
        return null
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
                val nextWeek = WEEKDAY_NEXT_WEEK.containsMatchIn(remaining)
                remaining = stripWeekDays(remaining)
                val date = RecurrenceEngine.firstOnOrAfter(
                    RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days),
                    today,
                    today,
                )
                return DateHit(
                    date?.let { if (nextWeek) nextWeekOf(it, today) else it },
                    remaining,
                    false,
                )
            }
            if (days.size > 1) {
                return DateHit(null, remaining, true)
            }
        }

        return DateHit(null, remaining, false)
    }

    private data class PeriodHit(val hint: String?, val label: String, val remaining: String)

    private fun extractPeriodHint(text: String): PeriodHit {
        val almoco = Regex("""\bdepois\s+do\s+almoco\b""")
        almoco.find(text)?.let {
            return PeriodHit("vague", "depois do almoço", text.replace(it.value, " "))
        }
        val night = Regex("""\b(?:a|da|de|na)\s+noite\b""")
        night.find(text)?.let {
            return PeriodHit("noite", "à noite", text.replace(it.value, " "))
        }
        val afternoon = Regex("""\b(?:a|da|de|na)\s+tarde\b""")
        afternoon.find(text)?.let {
            return PeriodHit("tarde", "à tarde", text.replace(it.value, " "))
        }
        val morning = Regex("""\b(?:a|da|de|na)\s+manha\b""")
        morning.find(text)?.let {
            return PeriodHit("manha", "de manhã", text.replace(it.value, " "))
        }
        return PeriodHit(null, "", text)
    }

    private fun applyPeriod(time: LocalTime, hint: String): LocalTime {
        if (hint == "vague") return time
        return LocalTime.of(applyPeriodHour(time.hour, "da $hint"), time.minute)
    }

    private fun applyPeriodHour(hourRaw: Int, period: String): Int = when {
        period.contains("tarde") && hourRaw in 1..11 -> hourRaw + 12
        period.contains("noite") && hourRaw in 1..11 -> hourRaw + 12
        // "às 12 da noite" é meia-noite; "às 12 da manhã" também.
        period.contains("noite") && hourRaw == 12 -> 0
        period.contains("manha") && hourRaw == 12 -> 0
        period.contains("madrugada") && hourRaw == 12 -> 0
        else -> hourRaw
    }

    /** "que vem"/"próxima" no dia da semana: a ocorrência da PRÓXIMA semana, não a desta. */
    private fun nextWeekOf(occurrence: LocalDate, today: LocalDate): LocalDate =
        if (sameIsoWeek(occurrence, today)) occurrence.plusWeeks(1) else occurrence

    private fun sameIsoWeek(a: LocalDate, b: LocalDate): Boolean =
        a.get(WeekFields.ISO.weekOfWeekBasedYear()) == b.get(WeekFields.ISO.weekOfWeekBasedYear()) &&
            a.get(WeekFields.ISO.weekBasedYear()) == b.get(WeekFields.ISO.weekBasedYear())

    private fun inferYear(today: LocalDate, month: Int, day: Int): Int {
        val candidate = try {
            YearMonth.of(today.year, month).atDay(day.coerceAtMost(YearMonth.of(today.year, month).lengthOfMonth()))
        } catch (_: Exception) {
            today
        }
        return if (candidate.isBefore(today)) today.year + 1 else today.year
    }

    private fun extractTitle(remaining: String, original: String): String {
        val leftover = TextNormalizer.compactSpaces(remaining)
            .split(" ")
            .filter { it.isNotBlank() && it !in FILLERS && !it.matches(Regex("\\d+h?")) }
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
        // "quinta que vem" / "próxima sexta": sai inteiro, senão "vem" sobra no título.
        var remaining = text.replace(WEEKDAY_NEXT_WEEK, " ")
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
        private val CLOCK_NUMERIC = Regex(
            """\b(?:as\s+)?(\d{1,2})(?:[:h](\d{2})|\s*h(?:oras?)?(?:\s*(\d{2}))?)(?:\s*(?:a|da|de|na)\s+(manha|tarde|noite|madrugada))?\b""",
        )
        private val CLOCK_WORD = Regex(
            """\bas\s+(uma|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze)(?:\s*h(?:oras?)?(?:\s*(\d{2}))?)?(?:\s*(?:a|da|de|na)\s+(manha|tarde|noite|madrugada))?\b""",
        )
        private val CLOCK_BARE = Regex(
            """\bas\s+(\d{1,2})\b(?:\s*(?:a|da|de|na)\s+(manha|tarde|noite|madrugada))?""",
        )

        private val MINUTE_TAIL = Regex(
            """\s+e\s+(meia|quinze|vinte|trinta|quarenta|cinquenta|\d{1,2})(?:\s+e\s+(um|dois|duas|tres|quatro|cinco|seis|sete|oito|nove))?\b""",
        )

        private val MINUTE_TAIL_WORDS = mapOf(
            "meia" to 30,
            "quinze" to 15,
            "vinte" to 20,
            "trinta" to 30,
            "quarenta" to 40,
            "cinquenta" to 50,
        )

        private val MINUTE_TENS = setOf("vinte", "trinta", "quarenta", "cinquenta")

        private val MINUTE_UNITS = mapOf(
            "um" to 1,
            "dois" to 2,
            "duas" to 2,
            "tres" to 3,
            "quatro" to 4,
            "cinco" to 5,
            "seis" to 6,
            "sete" to 7,
            "oito" to 8,
            "nove" to 9,
        )

        /** "de 8 em 8 horas", "a cada duas horas": intervalo entre doses. */
        private val INTERVAL = Regex(
            """\bde\s+(?:\d{1,2}|[a-z]+)\s+em\s+(?:\d{1,2}|[a-z]+)\s+(?:horas?|minutos?)\b""" +
                """|\b(?:a\s+)?cada\s+(?:\d{1,2}|[a-z]+)\s+(?:horas?|minutos?)\b""",
        )

        /** "daqui a duas horas e meia": o "e meia" depois do valor relativo vale 30 minutos. */
        private val MEIA_HORA_TAIL = Regex("""\s*e\s+meia(?:\s+horas?)?\b""")

        private const val WEEKDAY_ALT = "domingos?|segundas?|tercas?|quartas?|quintas?|sextas?|sabados?"

        /** "quinta que vem", "próxima sexta", "quinta da semana que vem". */
        private val WEEKDAY_NEXT_WEEK = Regex(
            """\b(?:$WEEKDAY_ALT)(?:-?feira)?\s+(?:(?:da\s+)?semana\s+)?(?:que\s+vem|proxim[ao]s?)\b""" +
                """|\bproxim[ao]s?\s+(?:$WEEKDAY_ALT)\b""",
        )

        private val WEEKDAY_PATTERNS = listOf(
            Regex("""\bdomingos?(?:-?feira)?\b""") to DayOfWeek.SUNDAY,
            Regex("""\bsegundas?(?:-?feira)?\b""") to DayOfWeek.MONDAY,
            Regex("""\btercas?(?:-?feira)?\b""") to DayOfWeek.TUESDAY,
            Regex("""\bquartas?(?:-?feira)?\b""") to DayOfWeek.WEDNESDAY,
            Regex("""\bquintas?(?:-?feira)?\b""") to DayOfWeek.THURSDAY,
            Regex("""\bsextas?(?:-?feira)?\b""") to DayOfWeek.FRIDAY,
            Regex("""\bsabados?(?:-?feira)?\b""") to DayOfWeek.SATURDAY,
        )

        private val WORD_HOURS = mapOf(
            "uma" to 1,
            "duas" to 2,
            "dois" to 2,
            "tres" to 3,
            "quatro" to 4,
            "cinco" to 5,
            "seis" to 6,
            "sete" to 7,
            "oito" to 8,
            "nove" to 9,
            "dez" to 10,
            "onze" to 11,
            "doze" to 12,
        )

        private val WORD_AMOUNTS = mapOf(
            "uma" to 1,
            "um" to 1,
            "duas" to 2,
            "dois" to 2,
            "tres" to 3,
            "quatro" to 4,
            "cinco" to 5,
            "seis" to 6,
            "sete" to 7,
            "oito" to 8,
            "nove" to 9,
            "dez" to 10,
            "onze" to 11,
            "doze" to 12,
            "treze" to 13,
            "catorze" to 14,
            "quatorze" to 14,
            "quinze" to 15,
            "dezesseis" to 16,
            "dezessete" to 17,
            "dezoito" to 18,
            "dezenove" to 19,
            "vinte" to 20,
            "vinte e cinco" to 25,
            "meia" to 30,
            "trinta" to 30,
            "quarenta" to 40,
            "quarenta e cinco" to 45,
            "cinquenta" to 50,
        )

        private val FILLERS = setOf(
            "me", "lembrar", "lembre", "lembra", "de", "que", "pra", "para", "o", "a", "os", "as",
            "um", "uma", "do", "da", "dos", "das", "no", "na", "em", "ao", "aos",
            "lembrete", "agendar", "agenda", "por", "favor", "preciso", "tenho",
            "marcar", "anota", "anotar", "tarefa", "compromisso", "e", "eh",
            "daqui", "hora", "horas", "minuto", "minutos", "meia",
            "noite", "manha", "tarde", "madrugada", "almoco", "depois",
            "cada", "mes", "ano", "nos", "nas",
        )
    }
}

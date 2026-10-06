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

        // Duas tarefas numa frase ("marcar médico terça e tomar remédio às oito"): antes o parser
        // colava tudo num título só, com o primeiro dia e a primeira hora, e a caixa rápida prometia
        // "Terça às 08:00" para as duas coisas. Ambíguo para escalar/confirmar, nunca adivinhar.
        if (looksLikeTwoTasks(folded)) {
            ambiguous = true
            confidence = minOf(confidence, 0.45)
            notes += "Parece haver mais de uma tarefa na mesma frase. Vamos separar?"
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

        // "todo dia 5 do mês" é mensal, mas "todo dia 5" sozinho é diário — sem o "do mês" ela
        // está falando do dia inteiro, não do 5º do mês. Exigir o "do mês" evita a recorrência
        // mensal silenciosa que aparecia quando ela omitia o "às". "no dia 15 do mês" (sem o
        // "todo") é a mesma coisa: com o "do mês", é o 15º dia, todo mês.
        //
        // "dia 25 do mês que vem" (sem o "todo") é uma data única no mês seguinte, não uma série:
        // sem a exclusão, "amanhã dia 25 do mês que vem às 9h" nascia MONTHLY. Mas com o "todo" a
        // frase É a série mensal no dia 25 — "todo dia 25 do mês que vem" (F6) —, então o "que
        // vem" só barra a forma sem o "todo".
        val monthly = Regex(
            """\bto[doas]+\s+dia\s+(\d{1,2})\s+do\s+mes\b""" +
                """|\b(?:nos?\s+)?dia\s+(\d{1,2})\s+do\s+mes\b(?!\s+que\s+vem)""",
        )
        monthly.find(remaining)?.let { m ->
            val day = m.groupValues[1].ifBlank { m.groupValues[2] }.toInt()
            remaining = remaining.replace(m.value, " ")
            return RecurrenceHit(
                RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = day),
                remaining,
                day !in 1..31,
            )
        }

        // "toda semana" é semanal (a série repete no mesmo dia da semana). Vem antes do "toda"
        // genérico: sem ele a frase ficava sem recorrência e com "Toda semana" colado no título.
        //
        // "toda semana NA TERÇA": quem manda é o dia da semana dito, e o "toda semana" só diz que
        // repete semanalmente. Sem o dia depois, a série ancorava em HOJE (a quinta do relógio) —
        // a caixa rápida confirmava terça com o dia errado, em silêncio (F1).
        EVERY_WEEK.find(remaining)?.let { m ->
            val after = remaining.substring(m.range.last + 1)
            val days = extractWeekDays(after)
            // "toda semana QUE VEM" é a semana seguinte — o "semana" já foi consumido pelo match,
            // então o WEEK_PHRASE é procurado no texto inteiro. Com um dia nomeado ("toda semana na
            // terça que vem") o próprio dia carrega o "que vem" e o deslocamento continua valendo.
            val nextWeek = WEEK_PHRASE.containsMatchIn(remaining) || WEEKDAY_NEXT_WEEK.containsMatchIn(after)
            remaining = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
            // O "que vem" pertence ao "toda semana" e sai junto — senão sobrava no título.
            if (nextWeek) remaining = TextNormalizer.compactSpaces(remaining.replaceFirst(NEXT_MONTH_TAIL, " "))
            if (days.isEmpty()) {
                // Sem dia nomeado, a série ancora no dia da semana de hoje — mas o "que vem" dito
                // ("toda semana que vem") tem que deslocar para a semana seguinte do mesmo jeito.
                // Sem propagar o `nextWeek`, a série começava HOJE, completa e não-ambígua, e a
                // caixa rápida confirmava o dia errado em silêncio (P0-1). O caminho com dia
                // nomeado logo abaixo já propagava.
                return RecurrenceHit(RecurrenceRule(RecurrenceKind.WEEKLY), remaining, false, nextWeek)
            }
            remaining = TextNormalizer.compactSpaces(stripWeekDays(remaining))
            return RecurrenceHit(
                RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days),
                remaining,
                false,
                nextWeek,
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

    /**
     * Extrai o horário. O intervalo em dias ("de 15 em 15 dias") sai do texto antes: ele não é
     * uma hora, mas a hora dita depois dele ("... às 9h") continua valendo. A ambiguidade do
     * intervalo entra por cima do resultado do relógio.
     */
    private fun extractTime(text: String): TimeHit {
        val intervalDay = INTERVAL_DAY.find(text)
            ?: return stripEmPonto(extractClockTime(text))
        val remaining = TextNormalizer.compactSpaces(text.replace(intervalDay.value, " "))
        val hit = stripEmPonto(extractClockTime(remaining))
        val note = "“${intervalDay.value}” é um intervalo, não uma data. Confirme o dia e o horário da primeira vez."
        return hit.copy(
            ambiguous = true,
            note = listOfNotNull(hit.note, note).joinToString(" "),
        )
    }

    /**
     * "em ponto" qualifica a hora e não é tarefa: sozinho no resto da frase virava o título
     * ("oito e meia em ponto" → "Ponto"). Só sai quando uma hora foi de fato reconhecida.
     */
    private fun stripEmPonto(hit: TimeHit): TimeHit {
        if (hit.time == null || !EM_PONTO.containsMatchIn(hit.remaining)) return hit
        return hit.copy(remaining = TextNormalizer.compactSpaces(hit.remaining.replace(EM_PONTO, " ")))
    }

    private fun extractClockTime(text: String): TimeHit {
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
            val date = TextNormalizer.compactSpaces(marker + relative.remaining)
            return TimeHit(relative.time, date, false)
        }

        // "daqui a pouco" é relativo, mas o quanto é vago demais para virar hora exata. Marca
        // ambíguo para ela confirmar em vez de cravar um horário inventado.
        SOON.find(remaining)?.let { m ->
            remaining = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
            return TimeHit(
                null,
                remaining,
                true,
                "“daqui a pouco” não é um horário exato. Confirme a hora.",
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

        // "às vinte e cinco de maio": o "às" abre o dia do mês por extenso, não uma hora. Sem isto o
        // "vinte" virava 20:00 e o resto ("cinco de maio") virava o dia 5. Devolve o dia por extenso
        // para extractDate e segue com o resto da frase (pode haver uma hora de verdade mais adiante).
        AS_DAY_OF_MONTH.find(remaining)?.let { m ->
            remaining = remaining.replaceRange(
                m.range.first,
                m.range.last + 1,
                m.groupValues[1] + " de " + m.groupValues[2],
            )
        }

        // "às vinte e cinco"/"às vinte e quatro": o composto passa de 23h e não é hora válida. Antes
        // o CLOCK_WORD casava só o "vinte" (20:00) e o "cinco" sobrava no título — 20h com cara de
        // certeza para uma hora que ela não disse. Ambíguo, para ela repetir.
        INVALID_HOUR_WORD.find(remaining)?.let { m ->
            remaining = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
            return TimeHit(null, remaining, true, "“${m.value}” não é um horário válido. Confirme a hora.")
        }

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
        CLOCK_BARE_WORD.findAll(remaining).forEach { m ->
            // "às vinte e cinco": o "vinte e cinco" é o dia do mês, não 20:25. Se o "às" que precede
            // a hora começa um composto, a regex longa casa o composto inteiro; a curta começaria
            // dentro dele ("vinte") e a palavra da unidade sobraria no título.
            val longMatch = found.any { it.match.range.first <= m.range.first && m.range.first <= it.match.range.last }
            val shorterThanLong = found.any {
                it.match.range.first <= m.range.first && m.range.last < it.match.range.last
            }
            if (longMatch && !shorterThanLong) return@forEach
            val hourRaw = WORD_HOURS[m.groupValues[1]] ?: return@forEach
            val raw = m.groupValues[2]
            val base = raw.toIntOrNull() ?: MINUTE_TAIL_WORDS[raw] ?: 0
            // "oito e vinte e cinco" sem o "às": a unidade depois da dezena é o minuto (25), senão o
            // "cinco" sumia da hora e sobrava no título.
            val unit = m.groupValues[3]
            val minute = if (unit.isBlank()) {
                base
            } else {
                if (raw !in MINUTE_TENS) return@forEach
                base + (MINUTE_UNITS[unit] ?: return@forEach)
            }
            found += ClockMatch(m, hourRaw, minute, m.groupValues[4])
        }
        // "três em ponto": hora por extenso sem o "às". Antes o "em ponto" vazava para o título
        // ("Ponto") e a hora se perdia. Não casa dentro de "às três" — o CLOCK_WORD já pegou.
        CLOCK_BARE_PONTO.findAll(remaining).forEach { m ->
            if (found.any { it.match.range.first <= m.range.first && m.range.first <= it.match.range.last }) return@forEach
            val hourRaw = WORD_HOURS[m.groupValues[1]] ?: return@forEach
            found += ClockMatch(m, hourRaw, 0, "")
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
        var period = hit.period
        // "às 12 e meia da noite": o período vem depois do "e meia", não colado no número — sem
        // isto a hora saía 12:30 (meio-dia) em vez de 00:30.
        if (period.isBlank()) {
            val after = hit.match.range.last + 1 + (consumed.length - hit.match.value.length)
            TRAILING_PERIOD.find(remaining, after)?.let { p ->
                if (p.range.first == after) {
                    period = p.groupValues[1]
                    consumed += p.value
                }
            }
        }
        // "às três" sem "da tarde"/"da manhã": 03:00 e 15:00 são igualmente plausíveis. Mantém o
        // palpite no rascunho, mas marca ambíguo para escalar — antes cravava madrugada com cara de
        // certeza. Só 1–6 ("cravar madrugada"): 7–11 é manhã quase sempre, e marcar "às nove" como
        // ambíguo mandaria a IA reparsear o que ela mais fala. "às 3 da tarde" já resolve em 15h
        // (applyPeriodHour) e, com o período dito, não vira ambíguo.
        val noPeriod = period.isBlank() && !PERIOD_PHRASE.containsMatchIn(text)
        val hour = applyPeriodHour(hit.hourRaw, period)
        remaining = remaining.replace(consumed, " ")
        return TimeHit(
            LocalTime.of(hour % 24, minute),
            remaining,
            ambiguous = noPeriod && hit.hourRaw in 1..6,
            note = "“$consumed” pode ser de manhã ou de tarde. Confirme o horário.",
        )
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

    /**
     * Relativo de relógio ("daqui a meia hora", "daqui a duas horas"): a hora e a data saem do
     * deslocamento no relógio. Os relativos de DIA ("daqui a dois dias", "em duas semanas") não
     * passam por aqui — a hora deles é a que ela disse (ou nenhuma) e a data vem de `extractDate`.
     */
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

    private data class DateHit(
        val date: LocalDate?,
        val remaining: String,
        val ambiguous: Boolean,
    )

    private fun extractDate(text: String, recurrence: RecurrenceRule): DateHit {
        var remaining = text
        val today = clock.today()

        // Data nomeada (Natal, Páscoa, Sexta-feira Santa, finados) e as expressões de mês
        // ("fim do mês", "meio do mês") vêm antes de tudo: sem isto "sexta-feira santa" casava o
        // dia da semana e virava a sexta DESTA semana, e "amanhã no fim do mês" entregava o
        // "amanhã" — data errada, completa e não-ambígua, que a caixa rápida confirmava em
        // silêncio, sem nunca consultar a IA. As duas são a expressão mais específica da frase e
        // por isso ganham do "amanhã"/"hoje", que sai do texto junto.
        namedDate(remaining, today)?.let { hit ->
            return DateHit(hit.date, stripDayWords(hit.remaining), false)
        }
        monthEdge(remaining, today, recurrence)?.let { hit ->
            return DateHit(hit.date, stripDayWords(hit.remaining), hit.ambiguous)
        }

        // "dia 25 do mês que vem": data única no mês seguinte. Vem antes do "amanhã" e do dia
        // avulso porque é a leitura específica — sem este ramo o mês era ignorado e a frase
        // virava o "amanhã" (ou uma série MONTHLY no dia 25 DESTE mês) sem avisar.
        NEXT_MONTH_DAY.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            remaining = stripDayWords(remaining.replace(m.value, " "))
            if (day !in 1..31) return DateHit(null, remaining, true)
            val next = today.plusMonths(1)
            return DateHit(
                RecurrenceEngine.clampToValidDate(next.year, next.monthValue, day),
                remaining,
                false,
            )
        }

        // "daqui a dois dias", "daqui a duas semanas": a hora já veio de `extractRelative`; aqui
        // só a data. Mesmo deslocamento, mesma regra de "amanhã".
        RELATIVE_DAY.find(remaining)?.let { m ->
            val raw = TextNormalizer.compactSpaces(m.groupValues[1])
            val n = raw.toIntOrNull() ?: WORD_AMOUNTS[raw] ?: return@let
            var consumed = m.value
            val isWeek = m.groupValues[2].startsWith("semana")
            var date = if (isWeek) today.plusWeeks(n.toLong()) else today.plusDays(n.toLong())
            // "daqui a duas semanas e meia": o "e meia" vale meia semana (3 dias), não zero. Sem
            // isto a data saía 7 dias antes e o "meia" sumia sem avisar (P2-8).
            if (isWeek) {
                MEIA_SEMANA_TAIL.find(remaining, m.range.last + 1)?.let { tail ->
                    if (tail.range.first == m.range.last + 1) {
                        date = date.plusDays(3)
                        consumed += tail.value
                    }
                }
            }
            remaining = stripDayWords(remaining.replace(consumed, " "))
            return DateHit(date, remaining, false)
        }

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

        // "dois de maio": o dia por extenso. A regex acima exige dígito, então a frase ficava sem
        // data e o "dois de maio" sobrava no título.
        val extensoPalavra = Regex(
            """\b($WORD_DAY_ALT)\s+de\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)(?:\s+de\s+(\d{4}))?\b""",
        )
        extensoPalavra.find(remaining)?.let { m ->
            val day = WORD_DAYS[TextNormalizer.compactSpaces(m.groupValues[1])] ?: return@let
            val month = monthFromName(m.groupValues[2])
            val year = m.groupValues[3].ifBlank { inferYear(today, month, day).toString() }.toInt()
            remaining = remaining.replace(m.value, " ")
            return DateHit(RecurrenceEngine.clampToValidDate(year, month, day), remaining, false)
        }

        // "no dia 25": dia do mês avulso. Sem mês dito, o próximo 25 (este mês se ainda não passou,
        // senão o do mês seguinte) — a regra de mês por extenso, mas com o dia em dígito.
        val dayOfMonth = Regex("""\b(?:no\s+)?dia\s+(\d{1,2})(?!\s*(?:de\s+cada|do\s+mes))\b""")
        val dayMatches = dayOfMonth.findAll(remaining).toList()
        if (dayMatches.size > 1) {
            // "no dia 25 e no dia 30": duas datas numa frase. Escolher a primeira em silêncio é o
            // mesmo defeito de colar duas tarefas — ambíguo, para ela separar.
            return DateHit(null, remaining, true)
        }
        dayMatches.firstOrNull()?.let { m ->
            val day = m.groupValues[1].toInt()
            remaining = remaining.replace(m.value, " ")
            if (day !in 1..31) return DateHit(null, remaining, true)
            val month = if (day >= today.dayOfMonth) today.monthValue else today.monthValue % 12 + 1
            val year = if (month >= today.monthValue) today.year else today.year + 1
            return DateHit(RecurrenceEngine.clampToValidDate(year, month, day), remaining, false)
        }

        // "semana que vem" sozinha: mesma data, próxima semana. "quinta que vem" tem dia e cai no
        // ramo de dia da semana abaixo, que já resolve a semana certa.
        if (extractWeekDays(remaining).isEmpty()) {
            WEEK_PHRASE.find(remaining)?.let { m ->
                remaining = remaining.replace(m.value, " ")
                return DateHit(today.plusWeeks(1), remaining, false)
            }
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

    /**
     * Datas nomeadas: as fixas do calendário (Natal, finados, namorados, mães, pais) e as móveis
     * deriváveis da Páscoa (Cinzas, Sexta-feira Santa, Corpus Christi).
     *
     * O que já resolvemos, resolvemos; o que não dá para derivar com segurança sai `null` de
     * propósito, para a frase continuar sem data e escalar, nunca virar a sexta desta semana em
     * silêncio. É o caso da Páscoa: cai entre 22/03 e 25/04, e chutar "este ano, 22 de março" com
     * cara de certeza é exatamente o erro que este ramo existe para impedir.
     */
    private fun namedDate(text: String, today: LocalDate): NamedDateHit? {
        NAMED_FIXED.forEach { (regex, target) ->
            regex.find(text)?.let { m ->
                if (commonNounUse(text, m)) return@let
                // "no Natal de 2027": o ano dito vence o palpite do relógio (P0-6). Sem isto o ano
                // era ignorado e a data caía no Natal do ano corrente — ou no do ano que vem, mesmo
                // quando ela disse um ano já passado.
                val yearTail = NAMED_YEAR_TAIL.find(text, m.range.last + 1)
                    ?.takeIf { it.range.first == m.range.last + 1 }
                val remaining = namedRemaining(text, m, yearTail)
                if (target == null) return NamedDateHit(null, remaining)
                val year = yearTail?.groupValues?.get(1)?.toInt() ?: run {
                    val month = today.monthValue
                    if (month < target.month || (month == target.month && today.dayOfMonth <= target.day)) {
                        today.year
                    } else {
                        today.year + 1
                    }
                }
                return NamedDateHit(
                    RecurrenceEngine.clampToValidDate(year, target.month, target.day),
                    remaining,
                )
            }
        }

        NAMED_MOTHERS.find(text)?.let { m ->
            return NamedDateHit(
                nthWeekdayOf(mothersDayYear(today), 5, DayOfWeek.SUNDAY, 2),
                namedRemaining(text, m, null),
            )
        }
        NAMED_FATHERS.find(text)?.let { m ->
            return NamedDateHit(
                nthWeekdayOf(fathersDayYear(today), 8, DayOfWeek.SUNDAY, 2),
                namedRemaining(text, m, null),
            )
        }

        // As móveis derivam da Páscoa. A do ano corrente pode já ter passado (a Sexta-feira Santa
        // de 2026 foi em 03/04, e hoje é agosto): nesse caso vale a do ano que vem, como no Natal.
        val movable = listOf(
            // A mais específica primeiro: "corpus christi" e "sexta-feira santa" contêm palavras
            // que a regex de cinzas também casaria.
            Triple(NAMED_CORPUS, 60, false),
            Triple(NAMED_SEXTA_SANTA, -2, false),
            // "cinzas" no início da frase, sem o "quarta-feira de", é a cinza do fogão (P0-4).
            Triple(NAMED_CINZAS, -46, true),
        )
        movable.forEach { (regex, offset, sentenceStartIsNoun) ->
            regex.find(text)?.let { m ->
                if (commonNounUse(text, m, sentenceStartIsNoun)) return@let
                var date = pascoaOf(today.year).plusDays(offset.toLong())
                if (date.isBefore(today)) date = pascoaOf(today.year + 1).plusDays(offset.toLong())
                return NamedDateHit(date, namedRemaining(text, m, null))
            }
        }
        return null
    }

    /**
     * O resto da frase depois de o nome da festa sair. Quando não sobra nada, o próprio nome é a
     * tarefa: "natal" sozinha devolvia `title=''` — data completa, sem nome e sem ambiguidade, que
     * a caixa rápida confirmava em silêncio (P0-5). O `main` devolvia "Natal"/"Finados"; as
     * preposições do match ("no", "de") já são filtradas em `extractTitle`.
     */
    private fun namedRemaining(text: String, match: MatchResult, extra: MatchResult?): String {
        var remaining = text.replace(match.value, " ")
        if (extra != null) remaining = remaining.replace(extra.value, " ")
        return TextNormalizer.compactSpaces(remaining).ifBlank { match.value }
    }

    /**
     * Nome de festa usado como substantivo comum não é data: "minha terra NATAL" e "as CINZAS da
     * churrasqueira" viravam 25/12 e a Quarta-feira de Cinzas, completos e não-ambíguos, sem a IA
     * consultar (F4). O nome vale como data quando o token imediatamente antes é um determinante
     * ou preposição de data (ou o início da frase); qualquer outra palavra antes ("terra natal",
     * "as cinzas") denuncia o substantivo/adjetivo comum.
     *
     * [sentenceStartIsNoun] fecha a brecha do início da frase: `before` é "" ali, e "" é um dos
     * determinantes, então "cinzas da churrasqueira" no começo da frase virava Quarta-feira de
     * Cinzas (P0-4). Sem o "quarta-feira de" colado, o "cinzas" nu no início é substantivo comum.
     */
    private fun commonNounUse(
        text: String,
        match: MatchResult,
        sentenceStartIsNoun: Boolean = false,
    ): Boolean {
        val before = text.substring(0, match.range.first).trimEnd().substringAfterLast(' ')
        // O nome NU ("cinzas") é o substantivo em qualquer posição: a data é "quarta-feira de
        // cinzas". Um determinante antes não a transforma em data — "no cinzas"/"na cinzas" é a
        // mesma brecha do início da frase, deslocada (P2-4).
        if (sentenceStartIsNoun && !match.value.contains("feira")) return true
        return before !in DATE_DETERMINERS
    }

    /**
     * Ano do Dia das Mães / dos Pais: o 2º domingo do mês. A escolha é "o primeiro que ainda não
     * passou", não um corte por dia-do-mês — o corte antigo pulava um ano inteiro quando a data
     * caía depois do dia 10 e devolvia data no PASSADO no dia seguinte à festa (F3).
     */
    private fun mothersDayYear(today: LocalDate): Int {
        val thisYear = nthWeekdayOf(today.year, 5, DayOfWeek.SUNDAY, 2)
        return if (thisYear.isBefore(today)) today.year + 1 else today.year
    }

    private fun fathersDayYear(today: LocalDate): Int {
        val thisYear = nthWeekdayOf(today.year, 8, DayOfWeek.SUNDAY, 2)
        return if (thisYear.isBefore(today)) today.year + 1 else today.year
    }

    private data class NamedDateHit(val date: LocalDate?, val remaining: String)

    /** Data fixa do calendário, mês e dia. */
    private data class FixedDate(val month: Int, val day: Int)

    private fun nthWeekdayOf(year: Int, month: Int, dayOfWeek: DayOfWeek, n: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        val offset = (dayOfWeek.value - first.dayOfWeek.value + 7) % 7
        return first.plusDays((offset + (n - 1) * 7).toLong())
    }

    /** Domingo de Páscoa (algoritmo de Meeus/Jones/Butcher, calendário gregoriano). */
    private fun pascoaOf(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /**
     * Bordas do mês: "começo/início" (dia 1), "meio" (dia 15) e "fim/final" (último dia). Todas
     * caem no mês corrente se a data ainda não passou, senão no seguinte — a mesma regra do
     * `MONTH_START`. Antes só o "começo" existia: "no fim do mês pagar conta" ficava sem data e o
     * "Fim" ia para o título, e "amanhã no fim do mês" entregava o "amanhã" sem avisar.
     *
     * "do mês que vem" desloca para o mês seguinte: sem isso o "que vem" sumia e a borda caía no
     * mês ATUAL (F5). O "do mês" é opcional ("fim do mês que vem" e "fim do próximo mês").
     */
    private fun monthEdge(text: String, today: LocalDate, recurrence: RecurrenceRule): MonthEdgeHit? {
        // A borda é uma data única. Com recorrência mensal, "dia 25 do mês" é a regra que manda —
        // o "do mês" ali é o do dia, não uma borda.
        if (recurrence.kind == RecurrenceKind.MONTHLY) return null

        val edges = listOf(
            MONTH_START to { m: YearMonth -> m.atDay(1) },
            MONTH_MIDDLE to { m: YearMonth -> m.atDay(15) },
            MONTH_END to { m: YearMonth -> m.atEndOfMonth() },
        )
        edges.forEach { (regex, dateOf) ->
            regex.find(text)?.let { m ->
                // O "que vem"/"próximo" só desloca o mês quando está COLADO à borda ("fim do mês
                // que vem", que já vem dentro do match). Um "que vem" a distância pertence a outra
                // oração — "fim do mês às 10h, me diz o que vem antes" não é o mês que vem — e
                // deslocava a data em silêncio (P1-2). O mesmo para o "passado", que puxa o mês
                // para trás em vez de entregar uma borda futura ignorando o que ela disse.
                val tail = NEXT_MONTH_TAIL.find(text, m.range.last + 1)
                    ?.takeIf { glued(text, m, it) }
                val past = MONTH_PAST.find(text, m.range.last + 1)
                    ?.takeIf { glued(text, m, it) }
                val nextMonth = NEXT_MONTH_TAIL.containsMatchIn(m.value) ||
                    PROXIMO_MONTH.containsMatchIn(m.value) || tail != null
                val base = when {
                    past != null -> today.minusMonths(1)
                    nextMonth -> today.plusMonths(1)
                    else -> today
                }
                val candidate = dateOf(YearMonth.from(base))
                val edge = if (past != null || nextMonth || candidate.isAfter(today)) {
                    candidate
                } else {
                    dateOf(YearMonth.from(today.plusMonths(1)))
                }

                // A frase pode nomear o dia E a borda ("no fim do mês na sexta"): os dois valem, e
                // o dia dito escolhe qual dentro do mês da borda. Ignorá-lo entregava 31/08 (uma
                // segunda) completo e não-ambíguo, que a caixa rápida confirmava em silêncio (P0-1).
                val days = extractWeekDays(text)
                val date = edgeWeekday(edge, days, today)

                var remaining = text.replace(m.value, " ")
                if (tail != null) remaining = remaining.replace(tail.value, " ")
                if (past != null) remaining = remaining.replace(past.value, " ")
                // O dia dito já cumpriu o papel de escolher a data; deixá-lo no resto faria o
                // título repetir o dia ("Sexta pagar conta") ao lado da data já resolvida.
                if (days.size == 1) remaining = stripWeekDays(remaining)
                return MonthEdgeHit(
                    date = date,
                    remaining = TextNormalizer.compactSpaces(remaining),
                    // Dois dias ditos com uma borda só não têm um dia que os satisfaça: escalar é
                    // o desfecho honesto, como no bloco de dia da semana abaixo.
                    ambiguous = days.size > 1,
                )
            }
        }
        return null
    }

    /**
     * O dia da semana dito sobre a borda do mês: "no fim do mês na sexta" é a sexta daquele mês,
     * não a primeira sexta depois da borda. As duas coisas que ela disse têm de valer — o mês da
     * borda E o dia nomeado —, então a ocorrência do dia fica DENTRO do mês da borda (a última
     * até a borda). Só quando não há uma no mês (a borda é o dia 1 e o dia dito cai antes) ou a
     * última já passou é que vale a próxima, que pode ser do mês seguinte.
     *
     * Sem isto o dia dito era descartado e a caixa rápida confirmava 31/08 (uma segunda) como
     * "sexta" em silêncio (P0-1).
     */
    private fun edgeWeekday(edge: LocalDate, days: Set<DayOfWeek>, today: LocalDate): LocalDate {
        if (days.size != 1) return edge
        val day = days.first()
        val lastOnOrBefore = edge.minusDays(((edge.dayOfWeek.value - day.value + 7) % 7).toLong())
        if (YearMonth.from(lastOnOrBefore) == YearMonth.from(edge) && !lastOnOrBefore.isBefore(today)) {
            return lastOnOrBefore
        }
        return RecurrenceEngine.firstOnOrAfter(
            RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = days),
            today,
            edge,
        ) ?: edge
    }

    /**
     * O qualificador de mês ("que vem", "passado") só desloca a borda quando está COLADO a ela,
     * sem palavra no meio. Um "que vem" a distância pertence a outra oração e não é o mês que vem.
     */
    private fun glued(text: String, edge: MatchResult, qualifier: MatchResult): Boolean =
        text.substring(edge.range.last + 1, qualifier.range.first).isBlank()

    private data class MonthEdgeHit(
        val date: LocalDate,
        val remaining: String,
        val ambiguous: Boolean = false,
    )

    /**
     * "amanhã no fim do mês", "hoje no Natal": a expressão relativa e a nomeada não podem valer
     * as duas, e a nomeada é a específica. O "amanhã"/"hoje" sai do texto — senão sobraria no
     * título ("Amanhã") e ainda casaria o ramo de dia avulso.
     */
    private fun stripDayWords(text: String): String {
        var remaining = text.replace(Regex("""\bdepois\s+de\s+amanha\b"""), " ")
        remaining = remaining.replace(Regex("""\bamanha\b|\bhoje\b"""), " ")
        return TextNormalizer.compactSpaces(remaining)
    }

    private data class PeriodHit(val hint: String?, val label: String, val remaining: String)

    private fun extractPeriodHint(text: String): PeriodHit {
        val almoco = Regex("""\bdepois\s+do\s+almoco\b""")
        almoco.find(text)?.let {
            return PeriodHit("vague", "depois do almoço", text.replace(it.value, " "))
        }
        // "à noitinha"/"à noitezinha" são uma palavra só: \bnoite\b não casa dentro delas.
        val night = Regex("""\b(?:a|da|de|na)\s+(?:noite|noitinha|noitezinha)\b""")
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
        // "da noite" só soma 12 de 7h em diante ("às 8 da noite" = 20h). Com 1–6 a madrugada é a
        // leitura natural — "às 3 e meia da noite" é 03:30, não 15:30.
        period.contains("noite") && hourRaw in 7..11 -> hourRaw + 12
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

    /**
     * Duas orações com verbo de tarefa cada ("... e ...") são duas tarefas, não uma. A primeira
     * versão colava as duas num título só, com o primeiro dia e a primeira hora — e a caixa rápida
     * prometia certeza para as duas de uma vez.
     *
     * Também pega a segunda oração sem verbo ("marcar médico terça e remédio às oito"), que é a
     * forma natural na fala: o dia numa oração e a hora em outra denunciam tarefas diferentes. O
     * guard do dia da semana evita o falso positivo de série ("toda terça e quinta às 18h"), em que
     * o "e" lista dias, não tarefas.
     */
    private fun looksLikeTwoTasks(text: String): Boolean {
        // "vinte e cinco"/"quarenta e cinco": o "e" aqui é do número, não separa orações. Colar as
        // duas palavras antes de dividir evita partir "às vinte e cinco de maio" em duas orações.
        val glued = text.replace(NUMBER_E, "$1$2")
        val clauses = glued.split(Regex("""\be\b"""))
        if (clauses.count { TASK_VERB.containsMatchIn(it) } >= 2) return true

        // "toda terça e quinta natação às 18h": o "e" lista dias da mesma série, não tarefas.
        val weekdayList = clauses.dropLast(1).indices.any { i ->
            endsWithWeekday(clauses[i]) && startsWithWeekday(clauses[i + 1])
        }
        if (weekdayList) return false

        if (clauses.none { TASK_VERB.containsMatchIn(it) }) return false

        // O dia numa oração e a hora em outra denunciam tarefas diferentes, mesmo sem verbo na
        // segunda ("marcar médico terça e remédio às oito"). Dia e hora na MESMA oração são uma
        // tarefa só ("buscar as crianças amanhã às 15h").
        return clauses.indices.any { i ->
            DATE_SIGNAL.containsMatchIn(clauses[i]) &&
                clauses.indices.any { j -> j != i && TIME_SIGNAL.containsMatchIn(clauses[j]) }
        }
    }

    private fun startsWithWeekday(clause: String): Boolean =
        WEEKDAY_ANY.containsMatchIn(clause.trimStart().substringBefore(' '))

    private fun endsWithWeekday(clause: String): Boolean =
        WEEKDAY_ANY.containsMatchIn(clause.trimEnd().substringAfterLast(' '))

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
        return TextNormalizer.compactSpaces(stripFeiraSuffix(remaining))
    }

    /**
     * Tira o "feira" que sobra do dia da semana ("sexta feira", depois de o dia já ter saído).
     * O substantivo (o mercado) fica: quando "feira" vem depois de artigo ou preposição — "na
     * feira", "da feira" — não é o sufixo do dia. Sem o guard, "ir na feira sábado" virava "Ir",
     * o nome da tarefa apagado e sem ambiguidade, que a caixa rápida confirmava sozinha.
     *
     * O mesmo vale quando o "feira" encabeça um sintagma nominal ("feira de ciências", "feira do
     * livro"): ali ele é o substantivo em qualquer posição, mesmo no início da frase, onde não há
     * preposição antes para denunciá-lo (P2-3).
     */
    private fun stripFeiraSuffix(text: String): String =
        FEIRA_SUFIXO.replace(text) { m ->
            val before = text.substring(0, m.range.first).trimEnd().substringAfterLast(' ')
            val headsNoun = FEIRA_NOUN_TAIL.containsMatchIn(text.substring(m.range.last + 1))
            // Sem nada antes o "feira" já não é o sufixo de um dia: o dia (segunda a sexta) teria
            // saído junto com ele. Sobrou porque é o substantivo ("sábado feira" → "Feira").
            if (before in FEIRA_PREPOSICOES || headsNoun || before.isEmpty()) m.value else " "
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
            """\bas\s+($WORD_HOUR_ALT)(?:\s*h(?:oras?)?(?:\s*(\d{2}))?)?(?:\s*(?:a|da|de|na)\s+(manha|tarde|noite|madrugada))?\b""",
        )
        // O Vosk pt-BR costuma devolver a hora por extenso sem o "às" ("tomar remédio oito e meia").
        // Só reconhece com o "e <minutos>" colado, para não capturar "oito" solto no título.
        private val CLOCK_BARE_WORD = Regex(
            """\b($WORD_HOUR_ALT)\s+e\s+(meia|quinze|vinte|trinta|quarenta|cinquenta|\d{1,2})(?:\s+e\s+(um|dois|duas|tres|quatro|cinco|seis|sete|oito|nove))?(?:\s*(?:a|da|de|na)\s+(manha|tarde|noite|madrugada))?\b""" +
                // "vinte e cinco de maio" é o dia do mês, não 20:25 — o " de <mês>" desempata.
                """(?!\s+de\s+(?:janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\b)""",
        )
        private val CLOCK_BARE = Regex(
            """\bas\s+(\d{1,2})\b(?:\s*(?:a|da|de|na)\s+(manha|tarde|noite|madrugada))?""",
        )
        /** "três em ponto"/"3 em ponto": hora por extenso ou em dígito sem o "às". */
        private val CLOCK_BARE_PONTO = Regex("""\b(\d{1,2}|$WORD_HOUR_ALT)\s+em\s+ponto\b""")

        /** "em ponto" qualifica a hora dita; não é o nome da tarefa. */
        private val EM_PONTO = Regex("""\bem\s+ponto\b""")

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

        /**
         * "de 15 em 15 dias", "a cada duas semanas": intervalo em dias, não uma data. A hora do
         * dia não foi dita; marcar ambíguo para ela confirmar é melhor que cravar um horário.
         */
        private val INTERVAL_DAY = Regex(
            """\bde\s+(?:\d{1,2}|[a-z]+)\s+em\s+(?:\d{1,2}|[a-z]+)\s+(?:dias?|semanas?)\b""" +
                """|\b(?:a\s+)?cada\s+(?:\d{1,2}|[a-z]+)\s+(?:dias?|semanas?)\b""",
        )

        /** "toda semana" sozinha: série semanal. Exige o "toda" para não comer "semana que vem". */
        private val EVERY_WEEK = Regex("""\btodas?\s+(?:as?\s+)?semanas?\b""")

        /** "dia 25 do mês que vem": data única no mês seguinte, não série mensal. */
        private val NEXT_MONTH_DAY = Regex("""\bdia\s+(\d{1,2})\s+do\s+mes\s+que\s+vem\b""")

        /**
         * Bordas do mês: o último dia, o dia 15 e o dia 1 (mesmo tratamento do "começo"). O mês
         * pode vir com o qualificador junto ("do mês que vem", "do próximo mês") — antes só a
         * forma "do mês" + "que vem" casava, e "fim do próximo mês" ficava sem data com o "Fim
         * próximo" no título (P1-7).
         */
        private val MONTH_END = Regex("""\b(?:no\s+)?(?:fim|final)\s+do\s+(?:mes(?:\s+que\s+vem)?|proximo\s+mes)\b""")
        private val MONTH_MIDDLE = Regex("""\b(?:no\s+)?meio\s+do\s+(?:mes(?:\s+que\s+vem)?|proximo\s+mes)\b""")

        /** "fim do mês QUE VEM": a borda cai no mês seguinte, não no atual. */
        private val NEXT_MONTH_TAIL = Regex("""\bque\s+vem\b""")

        /** "fim do PRÓXIMO mês": o mesmo deslocamento do "que vem", na forma com adjetivo. */
        private val PROXIMO_MONTH = Regex("""\bproximo\s+mes\b""")

        /** "fim do mês PASSADO": a borda cai no mês anterior, não numa futura que ignora o dito. */
        private val MONTH_PAST = Regex("""\bpassad[ao]\b""")

        /** "no Natal DE 2027": o ano dito na data nomeada vence o palpite do relógio (P0-6). */
        private val NAMED_YEAR_TAIL = Regex("""\s+de\s+(\d{4})\b""")

        /** Datas fixas do calendário. `null` = sabida mas não derivável com segurança (Páscoa). */
        private val NAMED_FIXED: List<Pair<Regex, FixedDate?>> = listOf(
            // "de natal" cobre "dia de Natal"/"véspera de Natal"; o `commonNounUse` separa de
            // "terra natal" (F4).
            Regex("""\b(?:no\s+|em\s+|de\s+)?natal\b""") to FixedDate(12, 25),
            Regex("""\b(?:dia\s+de\s+)?finados\b""") to FixedDate(11, 2),
            Regex("""\bdia\s+dos\s+namorados\b""") to FixedDate(6, 12),
            // A Páscoa cai entre 22/03 e 25/04: sem o ano, cravar uma data seria chute. Sem data,
            // a frase escala — o desfecho honesto.
            Regex("""\b(?:na\s+|no\s+|em\s+)?pascoa\b""") to null,
        )

        /**
         * Determinantes que deixam um nome de festa valer como data ("no Natal", "dia de Natal",
         * "na quarta-feira de cinzas", "as cinzas missa" é comum, "quarta-feira de cinzas" é data).
         * O vazio é o início da frase. Qualquer outra palavra antes ("terra natal", "as cinzas")
         * é o substantivo comum (F4).
         */
        private val DATE_DETERMINERS = setOf(
            "", "no", "na", "em", "de", "do", "da", "dia", "pro", "pra", "proximo", "proxima",
        )

        /**
         * "daqui a dois dias"/"daqui a duas semanas"/"em duas semanas": deslocamento de dia, não
         * de relógio. O "em" entra aqui porque é como ela fala ("em duas semanas dentista") e sem
         * ele a frase ficava pela metade. Não colide com "de 15 em 15 dias": o intervalo é
         * consumido antes, em `extractTime`.
         */
        private val RELATIVE_DAY = Regex(
            """\b(?:daqui(?:\s+a)?|em)\s+(\d+|[a-z]+(?:\s+e\s+[a-z]+)?)\s+(dias?|semanas?)(?!\w)""",
        )

        /** Dia das Mães / dos Pais: segundo domingo de maio e de agosto. */
        private val NAMED_MOTHERS = Regex("""\bdia\s+das\s+maes\b""")
        private val NAMED_FATHERS = Regex("""\bdia\s+dos\s+pais\b""")

        /** Móveis derivadas da Páscoa: cinzas (-46), sexta-feira santa (-2), corpus christi (+60). */
        private val NAMED_CINZAS = Regex("""\b(?:quarta[-\s]?feira\s+de\s+)?cinzas\b""")
        // "feira" é opcional: sem isso "sexta santa" sozinha caía no dia da semana comum e
        // devolvia a PRÓXIMA sexta — o mesmo defeito que este ramo existe para matar (F7).
        private val NAMED_SEXTA_SANTA = Regex("""\bsexta(?:[-\s]?feira)?\s+santa\b""")
        private val NAMED_CORPUS = Regex("""\bcorpus\s+christi\b""")

        /** "daqui a duas horas e meia": o "e meia" depois do valor relativo vale 30 minutos. */
        private val MEIA_HORA_TAIL = Regex("""\s*e\s+meia(?:\s+horas?)?\b""")

        /** "daqui a duas semanas e meia": meia semana = 3 dias, não zero (P2-8). */
        private val MEIA_SEMANA_TAIL = Regex("""\s+e\s+meia(?:\s+semanas?)?\b""")

        private const val WEEKDAY_ALT = "domingos?|segundas?|tercas?|quartas?|quintas?|sextas?|sabados?"

        /** "quinta que vem", "próxima sexta", "quinta da semana que vem". */
        private val WEEKDAY_NEXT_WEEK = Regex(
            """\b(?:$WEEKDAY_ALT)(?:-?feira|\s+feira)?\s+(?:(?:da\s+)?semana\s+)?(?:que\s+vem|proxim[ao]s?)\b""" +
                """|\bproxim[ao]s?\s+(?:$WEEKDAY_ALT)(?:-?feira|\s+feira)?\b""",
        )

        /**
         * O "feira" é opcional e vem com hífen OU espaço: "sexta-feira" e "sexta feira" são o mesmo
         * dia. Com só o hífen, "na sexta feira dentista" removia o "sexta" e deixava o "feira"
         * colado à preposição — que `stripFeiraSuffix` então poupava por achar que era o mercado, e
         * o título virava "Feira dentista" (P0-3). Consumindo o " feira" junto com o dia, não sobra
         * nada para o guard ver.
         *
         * A forma com ESPAÇO não come o "feira" quando ele encabeça um sintagma nominal ("feira
         * DE ciências", "feira DO livro"): ali o "feira" é o substantivo, não o sufixo do dia. A
         * forma com hífen continua inteira — o hífen é sinal forte do dia (P2-3).
         */
        private const val FEIRA_NOUN_TAIL_SRC = """(?:de|do|da|dos|das)\b"""
        private const val FEIRA_NOUN = """\s+feira(?!\s+$FEIRA_NOUN_TAIL_SRC)"""
        private val FEIRA_NOUN_TAIL = Regex("""^\s+$FEIRA_NOUN_TAIL_SRC""")
        private val WEEKDAY_PATTERNS = listOf(
            // "sábado"/"domingo" NÃO levam o sufixo "-feira" (só segunda a sexta): o "feira" depois
            // deles é o mercado, e consumi-lo apagava o nome da tarefa (P2-3).
            Regex("""\bdomingos?(?:-?feira)?\b""") to DayOfWeek.SUNDAY,
            Regex("""\bsegundas?(?:-?feira|$FEIRA_NOUN)?\b""") to DayOfWeek.MONDAY,
            Regex("""\btercas?(?:-?feira|$FEIRA_NOUN)?\b""") to DayOfWeek.TUESDAY,
            Regex("""\bquartas?(?:-?feira|$FEIRA_NOUN)?\b""") to DayOfWeek.WEDNESDAY,
            Regex("""\bquintas?(?:-?feira|$FEIRA_NOUN)?\b""") to DayOfWeek.THURSDAY,
            Regex("""\bsextas?(?:-?feira|$FEIRA_NOUN)?\b""") to DayOfWeek.FRIDAY,
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
            // O Vosk pt-BR devolve a tarde/noite por extenso ("quinze horas", "vinte e três horas"):
            // sem estas entradas o mesmo recado funcionava no motor de dígitos e falhava no de palavras.
            "treze" to 13,
            "catorze" to 14,
            "quatorze" to 14,
            "quinze" to 15,
            "dezesseis" to 16,
            "dezessete" to 17,
            "dezoito" to 18,
            "dezenove" to 19,
            "vinte" to 20,
            "vinte e uma" to 21,
            "vinte e um" to 21,
            "vinte e duas" to 22,
            "vinte e dois" to 22,
            "vinte e tres" to 23,
        )

        private const val WORD_HOUR_ALT =
            "uma|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|catorze|quatorze|" +
                "quinze|dezesseis|dezessete|dezoito|dezenove|vinte(?:\\s+e\\s+(?:uma|um|duas|dois|tres))?"

        /** Período dito em qualquer ponto ("hoje à noite às nove"): desfaz a ambiguidade de hora 1–6. */
        private val PERIOD_PHRASE = Regex("""\b(?:a|da|de|na)\s+(?:manha|tarde|noite|noitinha|noitezinha|madrugada)\b""")

        /** "semana que vem" sozinha (sem dia da semana): a data é a próxima semana, no mesmo dia. */
        private val WEEK_PHRASE = Regex("""\b(?:na\s+|da\s+)?semana\s+que\s+vem\b""")

        /** "no começo/início do mês": primeiro dia do mês seguinte quando o dia 1 já passou. */
        private val MONTH_START = Regex("""\b(?:no\s+)?(?:comeco|inicio)\s+do\s+(?:mes(?:\s+que\s+vem)?|proximo\s+mes)\b""")

        /** "daqui a pouco": o horário exato não foi dito — não inventamos, só marcamos para confirmar. */
        private val SOON = Regex("""\bdaqui\s+a\s+pouco\b""")

        /** Dia do mês por extenso ("dois de maio"): a regex de data exigia dígito e sobrava no título. */
        private val WORD_DAYS = mapOf(
            "uma" to 1, "um" to 1,
            "duas" to 2, "dois" to 2,
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
            "catorze" to 14, "quatorze" to 14,
            "quinze" to 15,
            "dezesseis" to 16,
            "dezessete" to 17,
            "dezoito" to 18,
            "dezenove" to 19,
            "vinte" to 20,
            "vinte e uma" to 21, "vinte e um" to 21,
            "vinte e duas" to 22, "vinte e dois" to 22,
            "vinte e tres" to 23,
            "vinte e quatro" to 24,
            "vinte e cinco" to 25,
            "vinte e seis" to 26,
            "vinte e sete" to 27,
            "vinte e oito" to 28,
            "vinte e nove" to 29,
            "trinta" to 30,
            "trinta e uma" to 31, "trinta e um" to 31,
        )

        // Compostos antes das unidades, senão "vinte e cinco" casaria só o "vinte".
        private const val WORD_DAY_ALT =
            "trinta(?:\\s+e\\s+(?:uma|um))?|vinte(?:\\s+e\\s+(?:uma|um|duas|dois|tres|quatro|cinco|seis|sete|oito|nove))?" +
                "|dezenove|dezoito|dezessete|dezesseis|quinze|quatorze|catorze|treze|doze|onze|dez|nove|oito|sete|seis|cinco" +
                "|quatro|tres|duas|dois|uma|um"

        /** "às 12 e meia da noite": o período vem depois do minuto, não colado no número. */
        private val TRAILING_PERIOD = Regex("""\s+(?:a|da|de|na)\s+(manha|tarde|noite|madrugada)\b""")

        /** "às vinte e cinco de maio": o "às" abre o dia do mês por extenso, não uma hora. */
        private val AS_DAY_OF_MONTH = Regex(
            """\bas\s+($WORD_DAY_ALT)\s+de\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\b""",
        )

        /** "às vinte e quatro".."às vinte e nove": composto acima de 23h — não é hora válida. */
        private val INVALID_HOUR_WORD = Regex(
            """\bas\s+vinte\s+e\s+(?:quatro|cinco|seis|sete|oito|nove)\b""",
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

        /** Verbo de tarefa que denuncia uma segunda oração depois do "e". */
        private val TASK_VERB = Regex(
            """\b(?:tomar|tomara|marcar|marca|ligar|liga|comprar|compra|pagar|paga|buscar|busca|""" +
                """levar|leva|pegar|pega|beber|bebe|comer|come|ir|vou|fazer|faz|enviar|envia|""" +
                """mandar|manda|reservar|reserva|revisar|revisa|consultar|consulta|avisar|avisa|""" +
                """encontrar|encontra|visitar|visita|limpar|limpa|lavar|lava|cozinhar|cozinha|""" +
                """estudar|estuda|treinar|treina|caminhar|caminha|correr|leve|anotar|anota)\b""",
        )

        /** Dia da semana solto — para distinguir "terça e quinta" (lista) de "terça e remédio". */
        private val WEEKDAY_ANY = Regex("""\b(?:$WEEKDAY_ALT)(?:-?feira)?\b""")

        /** O "-feira" que sobra do dia da semana; o substantivo (mercado) fica (ver `stripFeiraSuffix`). */
        private val FEIRA_SUFIXO = Regex("""\bfeiras?\b""")

        /** Preposições/artigos que fazem "feira" ser o mercado, não o sufixo do dia. */
        private val FEIRA_PREPOSICOES = setOf("na", "a", "da", "de", "pra", "para")

        /** "vinte e cinco"/"oito e meia": o "e" pertence ao número — não separa orações. */
        private val NUMBER_E = Regex(
            """\b(meia|uma|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|catorze|""" +
                """quatorze|quinze|dezesseis|dezessete|dezoito|dezenove|vinte|trinta|quarenta|cinquenta)""" +
                """\s+e\s+(meia|uma|um|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|vinte|trinta|quarenta|cinquenta)\b""",
        )

        /** Onde a oração fala de um dia: dia da semana, hoje/amanhã, "dia N", data por extenso. */
        private val DATE_SIGNAL = Regex(
            """\b(?:$WEEKDAY_ALT)(?:-?feira)?\b""" +
                """|\bhoje\b|\bamanha\b|\bdepois\s+de\s+amanha\b""" +
                """|\bdia\s+\d{1,2}\b|\bsemana\s+que\s+vem\b|\b\d{1,2}[/-]\d{1,2}\b""" +
                """|\b(?:$WORD_DAY_ALT)\s+de\s+(?:janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\b""" +
                """|\b\d{1,2}\s+de\s+(?:janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)\b""",
        )

        /** Onde a oração fala de uma hora: "às N", "às <palavra>", meio-dia, "daqui a ...". */
        private val TIME_SIGNAL = Regex(
            """\bas\s+(?:\d{1,2}|[a-z])|\bmeio[-\s]?dia\b|\bmeia[-\s]?noite\b|\bdaqui\b""",
        )

        private val FILLERS = setOf(
            "me", "lembrar", "lembre", "lembra", "de", "que", "pra", "para", "o", "a", "os", "as",
            "um", "uma", "do", "da", "dos", "das", "no", "na", "em", "ao", "aos",
            "lembrete", "agendar", "agenda", "por", "favor", "preciso", "tenho",
            "marcar", "anota", "anotar", "tarefa", "compromisso", "e", "eh",
            "daqui", "hora", "horas", "minuto", "minutos", "meia",
            "noite", "manha", "tarde", "madrugada", "depois",
            "cada", "mes", "ano", "nos", "nas",
        )
    }
}

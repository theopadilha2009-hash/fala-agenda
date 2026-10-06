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
        val periodHint = periodHit.hint
        if (periodHint != null && periodHint.contains("-")) {
            // Faixa do dia ("meio da tarde às quatro"): é um horário aproximado, não a hora que ela
            // disse. Sem hora, fica ambíguo (nunca inventa). Com hora, o período da faixa resolve o
            // palpite ("às quatro" → 16h); se o resultado cair fora da faixa, a hora dita e a faixa
            // se contradizem — ambíguo, em vez de cravar um dos dois em silêncio.
            if (localTime == null) {
                ambiguous = true
                confidence = minOf(confidence, 0.5)
                notes += "“${periodHit.label}” é uma faixa do dia, não uma hora exata. Complete o horário — não inventamos."
            } else {
                val period = periodHint.substringAfter("-")
                val resolved = LocalTime.of(applyPeriodHour(localTime.hour, "da $period") % 24, localTime.minute)
                localTime = resolved
                if (resolved.hour !in faixaHourRange(periodHint)) {
                    ambiguous = true
                    confidence = minOf(confidence, 0.5)
                    notes += "“${periodHit.label}” é uma faixa do dia, não a hora que você disse. Confirme o horário."
                }
            }
        } else if (periodHint != null) {
            // O PERÍODO EXPLÍCITO vence o marco: "antes do jantar às seis da manhã" é 06:00 — o "da
            // manhã" que ela disse não pode ser sobrescrito pelo jantar (≈20h).
            if (timeHit.hadPeriod) {
                // Horário já resolvido pelo período dito; nada a fazer.
            } else if (periodHint == "jantar") {
                // "jantar" é ≈20h. No "depois", a hora 1–6 somada dá 13–18h — a tarde, que é ANTES
                // do jantar; contradiz o "depois". No "antes", 1–6 vira jantar−2h (18h) e 7–11
                // vira 19–23h — depois do jantar, contradiz o "antes"; e o "às oito" (20h) já É o
                // jantar, não "antes" dele. Nesses casos ambíguo, não crava.
                val antesDoJantar = periodHit.label == "antes do jantar"
                val contradiz = localTime != null &&
                    if (antesDoJantar) {
                        localTime.hour in 7..11 || localTime.hour == 20
                    } else {
                        localTime.hour in 1..6
                    }
                if (contradiz) {
                    ambiguous = true
                    confidence = minOf(confidence, 0.5)
                    notes += "“${periodHit.label}” e a hora dita não batem. Confirme o horário."
                } else if (localTime != null && localTime.hour in 1..6) {
                    // "antes do jantar às seis": o jantar é ≈20h, o "antes" é a hora da tarde —
                    // 18h, não 06:00.
                    localTime = LocalTime.of(localTime.hour + 12, localTime.minute)
                } else if (localTime != null && localTime.hour in 7..11) {
                    localTime = applyPeriod(localTime, periodHint)
                } else if (localTime == null) {
                    ambiguous = true
                    confidence = minOf(confidence, 0.5)
                    notes += "“${periodHit.label}” não é um horário exato. Complete o horário — não inventamos."
                }
            } else if (localTime != null && localTime.hour in 1..11) {
                localTime = applyPeriod(localTime, periodHint)
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
            // "tomar duas da manhã e duas da noite": uma hora só no rascunho ainda é uma hora
            // completa com uma dose a menos. Sem hora, ela confirma em vez de a agenda comer uma.
            localTime = null
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
        val monthly = Regex("""\b(?:(?:to[doas]+|nos?)\s+)?dia\s+(\d{1,2})\s+do\s+mes\b""")
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
        /** A hora veio com o período dito ("da manhã"): o horário já está resolvido. */
        val hadPeriod: Boolean = false,
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
        if (found.size > 1) {
            return TimeHit(null, remaining, true)
        }
        // D3 revertido: "duas da tarde" sem o "às" era reconhecido como hora por compensar a
        // hipótese de que o Vosk derruba o "às" — hipótese nunca medida. Sem o "às" não dá para
        // separar hora ("duas da tarde") de dose ("duas da manhã"), data ("25/12 da tarde") e
        // recorrência ("todo dia 5 da tarde"), e a regra cravava hora/data errada com
        // ambiguous=false. Na dúvida, ambíguo: escala para a IA em vez de agendar errado em silêncio.
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
            consumirEmPonto(remaining),
            ambiguous = noPeriod && hit.hourRaw in 1..6,
            note = "“$consumed” pode ser de manhã ou de tarde. Confirme o horário.",
            hadPeriod = period.isNotBlank(),
        )
    }

    /**
     * O "em ponto" é QUALIFICADOR da hora, nunca fonte dela.
     *
     * O ramo antigo (`EM_PONTO_CLOCK`) procurava a hora por conta própria: casava um número antes do
     * "em ponto" e o transformava em hora, antes de `extractDate` rodar. Como `extractTime` vem
     * primeiro, ele engolia números que pertenciam a outra coisa — o dia do mês ("dia 12 em ponto"),
     * o primeiro número de uma data `NN/MM` ("05/12 em ponto" virava 05:12 de setembro) e a segunda
     * hora de uma frase ("oito em ponto e 9"). Cada rodada de review fechou uma dessas formas e
     * deixou a vizinha aberta, porque o defeito não era a lista de guardas: era o ramo ter opinião
     * própria sobre qual número é hora.
     *
     * Aqui ele não tem. Só age sobre uma hora que os ramos normais JÁ reconheceram, e o trabalho dele
     * é um só: tirar o "em ponto" do texto que sobra para o título. Sem hora reconhecida, não faz
     * nada — o "em ponto" fica onde está e não inventa horário nenhum.
     */
    private fun consumirEmPonto(remaining: String): String {
        val m = EM_PONTO_TAIL.find(remaining) ?: return remaining
        return TextNormalizer.compactSpaces(remaining.replaceRange(m.range.first, m.range.last + 1, " "))
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

        MONTH_START.find(remaining)?.let { m ->
            remaining = remaining.replace(m.value, " ")
            val first = today.withDayOfMonth(1)
            val date = if (first.isAfter(today)) first else first.plusMonths(1)
            return DateHit(date, remaining, false)
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

    private data class PeriodHit(val hint: String?, val label: String, val remaining: String)

    private fun extractPeriodHint(text: String): PeriodHit {
        val almoco = Regex("""\bdepois\s+do\s+almoco\b""")
        almoco.find(text)?.let {
            return PeriodHit("vague", "depois do almoço", text.replace(it.value, " "))
        }
        // "jantar" é o marco da noite (≈20:00), não um período vago como o almoço: "depois do jantar
        // às oito" saía 08:00 com faltam=[] e confiança alta — a caixa rápida confirmava sem consultar
        // a IA e a tarefa era agendada de manhã em silêncio. Sozinho ("depois do jantar"), também
        // precisa marcar ambíguo em vez de deixar a palavra no título.
        val jantar = Regex("""\b(depois|antes|apos)\s+do\s+jantar\b""")
        jantar.find(text)?.let { m ->
            // O rótulo segue o que ela disse: a nota de "antes do jantar" não pode dizer "depois do
            // jantar" e contradizer a própria frase.
            val antes = m.groupValues[1] == "antes"
            return PeriodHit("jantar", if (antes) "antes do jantar" else "depois do jantar", text.replace(m.value, " "))
        }
        // "meio da tarde"/"começo da manhã"/"fim de tarde": faixa do dia, não hora exata. Vinha
        // virando título ("Meio", "Começo", "Fim") com faltam=[] e confiança alta.
        val faixa = Regex("""\b(meio|comeco|fim)\s+d[aeo]\s+(manha|tarde|noite|madrugada)\b""")
        faixa.find(text)?.let { m ->
            return PeriodHit("${m.groupValues[1]}-${m.groupValues[2]}", m.value, text.replace(m.value, " "))
        }
        // "à noitinha"/"à noitezinha" são uma palavra só: \bnoite\b não casa dentro delas. "pela" é o
        // mesmo que "de": sem ele "pela manhã" vazava para o título ("Tomar remédio pela").
        val night = Regex("""\b(?:a|da|de|na|pela)\s+(?:noite|noitinha|noitezinha)\b""")
        night.find(text)?.let {
            return PeriodHit("noite", "à noite", text.replace(it.value, " "))
        }
        val afternoon = Regex("""\b(?:a|da|de|na|pela)\s+tarde\b""")
        afternoon.find(text)?.let {
            return PeriodHit("tarde", "à tarde", text.replace(it.value, " "))
        }
        val morning = Regex("""\b(?:a|da|de|na|pela)\s+manha\b""")
        morning.find(text)?.let {
            return PeriodHit("manha", "de manhã", text.replace(it.value, " "))
        }
        return PeriodHit(null, "", text)
    }

    private fun applyPeriod(time: LocalTime, hint: String): LocalTime {
        if (hint == "vague") return time
        return LocalTime.of(applyPeriodHour(time.hour, "da $hint"), time.minute)
    }

    /** Faixa de horas plausível de "meio/começo/fim de <período>" — a hora dita fora dela é corrigida. */
    private fun faixaHourRange(hint: String): IntRange = when (hint) {
        "comeco-manha" -> 7..9
        "meio-manha" -> 8..10
        "fim-manha" -> 10..11
        "comeco-tarde" -> 13..14
        "meio-tarde" -> 14..16
        "fim-tarde" -> 17..19
        "comeco-noite" -> 19..20
        "meio-noite" -> 20..22
        "fim-noite" -> 22..23
        "comeco-madrugada" -> 0..1
        "meio-madrugada" -> 1..3
        "fim-madrugada" -> 3..5
        else -> 0..23
    }

    private fun applyPeriodHour(hourRaw: Int, period: String): Int = when {
        period.contains("tarde") && hourRaw in 1..11 -> hourRaw + 12
        // "depois/antes do jantar às oito" é a noite (20h). O "antes do jantar às seis" (18h) já
        // resolvido no extractTime não é tocado aqui.
        period.contains("jantar") && hourRaw in 1..11 -> hourRaw + 12
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
        /**
         * "em ponto" como sufixo de uma hora que os relógios normais já reconheceram.
         *
         * É só o qualificador: não captura hora, minuto nem período — quem faz isso é `CLOCK_*`. O
         * ramo que capturava a hora por conta própria consumia o dia do mês ("dia 12 em ponto"), o
         * primeiro número de uma data `NN/MM` ("05/12 em ponto" → 05:12 de setembro) e a segunda hora
         * em dígito da frase ("oito em ponto e 9"), todos com `ambiguous = false`. Ver
         * `consumirEmPonto`.
         */
        private val EM_PONTO_TAIL = Regex("""\bem\s+ponto\b""")
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
        private val PERIOD_PHRASE = Regex(
            """\b(?:a|da|de|na|pela)\s+(?:manha|tarde|noite|noitinha|noitezinha|madrugada)\b|\b(?:depois|antes|apos)\s+do\s+jantar\b""",
        )

        /** "semana que vem" sozinha (sem dia da semana): a data é a próxima semana, no mesmo dia. */
        private val WEEK_PHRASE = Regex("""\b(?:na\s+|da\s+)?semana\s+que\s+vem\b""")

        /** "no começo do mês": primeiro dia do mês seguinte quando o dia 1 já passou. */
        private val MONTH_START = Regex("""\b(?:no\s+)?comeco\s+do\s+mes\b""")

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
            "noite", "manha", "tarde", "madrugada", "almoco", "depois",
            "cada", "mes", "ano", "nos", "nas",
        )
    }
}

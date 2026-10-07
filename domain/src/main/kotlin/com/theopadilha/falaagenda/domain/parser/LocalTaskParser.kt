package com.theopadilha.falaagenda.domain.parser

import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import com.theopadilha.falaagenda.domain.time.AppClock
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Parser determinístico pt-BR. Nunca inventa data/hora ausente.
 *
 * A `note` de cada `DateHit` nasce marcada com o prefixo de [NotasDoRascunho] — o casamento entre
 * o que este parser escreve e o que o `HybridParser` tem de desmentir é pelo prefixo, e não pela
 * frase inteira: antes a lista era de frases completas, e cada nota nova (a data que rolou o ano,
 * a que não existe) ficava de fora dela sem que nada avisasse, sobrevivendo à IA ter resolvido a
 * data e aparecendo em vermelho acima da data preenchida.
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

        // O valor em reais sai antes do relógio: "pagar 30 reais às 10h" tem dois números e só o
        // segundo é hora.
        var amountCents: Long? = null
        extractAmount(remaining)?.let { hit ->
            amountCents = hit.cents
            remaining = hit.remaining
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
            notes += dateHit.note ?: NotasDoRascunho.DATA_AMBIGUA
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
                notes += NotasDoRascunho.INSTANTE_PASSADO
            }
        }
        if (missing.contains(MissingDraftField.DATE)) {
            notes += NotasDoRascunho.FALTA_DATA
        }
        if (missing.contains(MissingDraftField.TIME)) {
            notes += NotasDoRascunho.FALTA_HORA
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
            amountCents = amountCents,
        )
    }

    private data class AmountHit(val cents: Long, val remaining: String)

    /**
     * O valor em reais que ela fala: "120 reais", "cento e vinte reais", "R$ 30", "mil e duzentos
     * reais". Vira centavos em `amountCents` — o campo já era renderizado na confirmação
     * (`QuickConfirmDialog`) e somado no mês (`MonthInsights`), e nenhum caminho o preenchia.
     *
     * A leitura só vale quando é inequívoca. "30.50" é ambíguo entre decimal e milhar malformado,
     * "tres reais e vinte" tem um conector que não fecha, "dois contos de réis" não é a unidade de
     * reais: nesses casos o valor fica nulo e o texto fica no título — inventar dinheiro que a
     * caixa rápida confirma em silêncio é a pior classe de defeito deste projeto.
     */
    private fun extractAmount(text: String): AmountHit? {
        REAIS_NUMERIC.find(text)?.let { m ->
            val numero = m.groups[1] ?: m.groups[3] ?: m.groups[5] ?: return@let
            val escala = m.groups[2] ?: m.groups[4] ?: m.groups[6]
            if (conectorSemCentavos(text, m)) return null
            val cents = centsFromNumber(numero.value) ?: return@let
            return withCentavos(escalaEmCentavos(cents, escala?.value) ?: return@let, text, m)
        }
        // Antes do extenso: "meio mil reais" é R$500, e sem este ramo o extenso casaria só o
        // "mil reais" e gravaria R$1.000 (o dobro). A unidade de dinheiro tem que estar dita:
        // "meio milhão de pessoas" não é valor nenhum.
        MEIO_MIL_ESCALA.find(text)?.let { m ->
            if (!unidadeDita(text, m)) return@let
            val cents = escalaMultiplier(m.groupValues[1]) * 50L
            return withCentavos(cents, text, m)
        }
        MEIO_REAL.find(text)?.let { m ->
            if (conectorSemCentavos(text, m)) return@let
            return AmountHit(50, TextNormalizer.compactSpaces(text.replace(m.value, " ")))
        }
        REAIS_EXTENSO.find(text)?.let { m ->
            if (conectorSemCentavos(text, m)) return null
            // "30.50 mil reais": o extenso re-ancorava no "mil reais" depois de um número em
            // dígito que o ramo numérico já recusou (o ponto não é milhar) e gravava R$1.000,00.
            if (precedidoDeDigito(text, m)) return null
            // "pagar meio reais": o "meio" vale zero no léxico e o extenso gravaria R$0,00 — dinheiro
            // inventado, confirmável em um toque. "meio milhão de reais" NÃO cai aqui: ele casa o
            // [MEIO_MIL_ESCALA], que é testado antes. O "meio real" dela são R$0,50, e vem do
            // [MEIO_REAL] — este guard só recusa o "meio" colado no plural, sem escala.
            if (m.value.trimStart().startsWith("meio ")) return null
            val cents = (numberFromWords(m.groupValues[1]) ?: return@let) * 100
            return withCentavos(cents, text, m)
        }
        // Sem ramo próprio para "N mil" sozinho: o número em dígito escalado só vale com a
        // unidade de dinheiro dita, e quem a exige é o [REAIS_NUMERIC] ("5 mil reais", "R$ 5 mil").
        // Sem a unidade não há o que distinguir entre pagar R$5.000 e caminhar 5 mil passos.
        return null
    }

    /**
     * O escalar dito ("15 mil", "1,5 milhão") sobre um número que já está em centavos: multiplica
     * por mil/milhão. `null` quando o produto estoura o `Long` — o valor vira nulo e a palavra fica
     * no título, em vez de estourar a exceção que derrubava a interpretação inteira.
     */
    private fun escalaEmCentavos(cents: Long, escala: String?): Long? {
        val multiplicador = escalaMultiplier(escala)
        if (cents != 0L && multiplicador > Long.MAX_VALUE / cents) return null
        return cents * multiplicador
    }

    /**
     * A unidade de dinheiro no próprio casamento ("meio mil reais", "meio milhão de reais") ou
     * logo depois dele, dentro do alcance do escalar: "meio milhão de pessoas" não tem nenhuma.
     */
    private fun unidadeDita(text: String, hit: MatchResult): Boolean =
        REAIS_TAIL.containsMatchIn(hit.value) ||
            REAIS_TAIL.containsMatchIn(caudaDoValor(text, hit).take(UNIDADE_ALCANCE))

    /**
     * O extenso com um número em DÍGITO imediatamente antes: "30.50 mil reais" casa só o
     * "mil reais" e gravava R$1.000,00. O dígito que o próprio valor recusou (o ponto não é
     * milhar) não pode virar dinheiro pelo pedaço que sobrou — o valor não sai e o texto fica no
     * título.
     */
    private fun precedidoDeDigito(text: String, hit: MatchResult): Boolean =
        text.substring(0, hit.range.first).trimEnd().lastOrNull()?.isDigit() == true

    /**
     * O conector "e" logo depois do valor, sem a palavra "centavos": "tres reais e vinte" não diz
     * se o "vinte" são centavos que ela não nomeou ou outra coisa. Com o conector opcional dentro
     * do casamento, o "e vinte" era engolido e sumia do título sem virar valor — perda dupla.
     * Sem a palavra "centavos" fechando o casamento, não extrai.
     */
    private fun conectorSemCentavos(text: String, reais: MatchResult): Boolean {
        val tail = caudaDoValor(text, reais)
        if (CENTAVOS.containsMatchIn(tail)) return false
        return CONECTOR_NUMERO.containsMatchIn(tail)
    }

    /**
     * A cauda logo depois do valor, começando na ÚLTIMA letra do casamento.
     *
     * O casamento pode terminar no espaço depois do número — o `\s*` do ramo do cifrão (`"r$ 120 "`)
     * e o do escalar (`"R$ 5 mil "`) consomem o espaço porque o `(?:reais|real)?` do fim é opcional.
     * Tirando a cauda de `range.last + 1`, ela começava em "e cinquenta..." e o conector não era
     * visto: o valor saía só o principal (12000 em vez de 12050) e a caixa rápida confirmava em um
     * toque. A âncora é o último CARACTERE do casamento, e `CONECTOR_NUMERO` já exige o espaço
     * antes do "e" — por isso o guard vale igual para `"120 reais"`, `"r$ 120 "` e `"R$ 5 mil "`.
     */
    private fun caudaDoValor(text: String, hit: MatchResult): String = text.substring(inicioDaCauda(hit))

    /** O índice em que a cauda do valor começa: o fim do casamento menos o espaço que ele comeu. */
    private fun inicioDaCauda(hit: MatchResult): Int {
        val espacosNoFim = hit.value.length - hit.value.trimEnd().length
        return hit.range.last + 1 - espacosNoFim
    }

    /**
     * "quinze reais e cinquenta centavos": o valor são 15,50, não 15,00 — e o "cinquenta centavos"
     * não pode sobrar no título. Os centavos vêm colados no "e", logo depois do valor.
     *
     * O "reais" que repete o cifrão ("R$ 1.234,56 reais") não precisa de limpeza própria: a
     * alternativa do cifrão em [REAIS_NUMERIC] já o consome no `(?:reais|real)?` do fim.
     */
    private fun withCentavos(cents: Long, text: String, reais: MatchResult): AmountHit {
        var total = cents
        var fim = reais.range.last + 1
        CENTAVOS.find(caudaDoValor(text, reais))?.let { tail ->
            val extra = centsFromSpoken(tail.groupValues[1])
            if (extra != null && extra in 0..99) {
                total += extra
                // O corte é por ÍNDICE na frase, e não pela concatenação do casamento com a cauda:
                // o casamento do cifrão já termina no espaço, e a string remontada teria dois
                // espaços onde a frase tem um — o `replace` não casaria e nada sairia do título.
                fim = inicioDaCauda(reais) + tail.range.last + 1
            }
        }
        return AmountHit(total, TextNormalizer.compactSpaces(text.replaceRange(reais.range.first, fim, " ")))
    }

    /** Os centavos ditos em dígito ("50") ou por extenso ("cinquenta e cinco"). */
    private fun centsFromSpoken(raw: String): Long? =
        raw.toLongOrNull() ?: numberFromWords(TextNormalizer.compactSpaces(raw))

    /**
     * "1.500" e "1.234,56" são milhar por ponto e decimal por vírgula. Só o ponto de milhar
     * (exatamente 3 dígitos, e nunca depois da vírgula) sai: "30.50" é ambíguo entre 30,50 e um
     * 30.500 malformado, e tirar o ponto dele inventava R$30,00 (no cifrão) ou R$50,00 (em "30.50
     * reais"). Na dúvida, o valor não é extraído e a palavra fica no título.
     *
     * O inteiro sem separador vale até quatro dígitos: "1500 reais" é o valor dito e não tem outra
     * leitura (com cinco ou mais, "12345" pode ser um milhar malformado — segue nulo). O ponto só
     * é milhar quando o ÚLTIMO grupo tem exatamente 3 dígitos: "12.345" e "1.234.567" valem, e
     * "1.23"/"12.3456" não.
     */
    private fun centsFromNumber(raw: String): Long? {
        val inteiro = raw.substringBefore(',')
        val ultimoGrupo = inteiro.substringAfterLast('.', "")
        if (ultimoGrupo.isNotEmpty() && ultimoGrupo.length != 3) return null
        if (!inteiro.matches(Regex("""\d{1,3}(?:\.\d{3})*|\d{1,$MAX_DIGITOS_INTEIRO}"""))) return null
        val normalizado = raw.replace(".", "").replace(',', '.')
        val emReais = normalizado.toBigDecimalOrNull() ?: return null
        // O que precisa caber no `Long` são os CENTAVOS, não os dígitos do inteiro em reais: o teto
        // sai do próprio tipo. O `toLong()` do `BigDecimal` estoura (exceção, não nulo) acima dele e
        // derrubava a interpretação inteira da frase; aqui o valor fica nulo e o texto vai para o
        // título. O mesmo número escrito com os pontos de milhar é lido — o teto é o do tipo, e não
        // a forma como ela separou os grupos.
        if (emReais > MAX_REAIS_INTERPRETAVEIS) return null
        return emReais.movePointRight(2).toLong()
    }

    /** "milhão"/"mil" por extenso depois do número em dígito: "15 mil" são 15.000. */
    private fun escalaMultiplier(escala: String?): Long = when (escala) {
        null, "" -> 1L
        "mil" -> 1000L
        "milhao", "milhoes" -> 1_000_000L
        else -> 1L
    }

    /**
     * "cento e vinte e cinco" = 125; "um milhão e duzentos mil" = 1.200.000. Nulo quando não há
     * número nenhum para ler.
     *
     * "milhão" escala o que vem antes (e o que já foi somado), como "mil" — sem ele o motor
     * re-ancorava em "duzentos mil" e gravava R$200 mil para "um milhão e duzentos mil reais".
     */
    private fun numberFromWords(phrase: String): Long? {
        var total = 0L
        var current = 0L
        var sawNumber = false
        for (word in phrase.split(" ")) {
            when {
                word == "e" -> Unit
                word == "milhao" || word == "milhoes" -> {
                    total = (total + (if (current == 0L) 1L else current)) * 1_000_000
                    current = 0
                    sawNumber = true
                }
                word == "mil" -> {
                    total += (if (current == 0L) 1L else current) * 1000
                    current = 0
                    sawNumber = true
                }
                word == "cem" || word == "cento" -> {
                    current += 100
                    sawNumber = true
                }
                NUMBER_WORDS.containsKey(word) -> {
                    current += NUMBER_WORDS.getValue(word)
                    sawNumber = true
                }
            }
        }
        return if (sawNumber) total + current else null
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
                !RecurrenceEngine.dayExistsInMonth(day, month),
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
                !RecurrenceEngine.dayExistsInMonth(day, month),
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
        /** A hora veio com o período dito ("da manhã"): o horário já está resolvido. */
        val hadPeriod: Boolean = false,
    )

    /**
     * Extrai o horário. O intervalo em dias ("de 15 em 15 dias") sai do texto antes: ele não é
     * uma hora, mas a hora dita depois dele ("... às 9h") continua valendo. A ambiguidade do
     * intervalo entra por cima do resultado do relógio.
     */
    private fun extractTime(text: String): TimeHit {
        val intervalDay = INTERVAL_DAY.find(text) ?: return extractClockTime(text)
        val remaining = TextNormalizer.compactSpaces(text.replace(intervalDay.value, " "))
        val hit = extractClockTime(remaining)
        val note = "“${intervalDay.value}” é um intervalo, não uma data. Confirme o dia e o horário da primeira vez."
        return hit.copy(
            ambiguous = true,
            note = listOfNotNull(hit.note, note).joinToString(" "),
        )
    }

    private fun extractClockTime(text: String): TimeHit {
        var remaining = text

        // "de 8 em 8 horas", "a cada 2 horas": intervalo entre doses, não um horário do dia.
        INTERVAL.find(remaining)?.let { m ->
            val rest = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
            // "a cada 12 horas começando amanhã às 8h": o "8h" é a primeira dose, não um horário
            // qualquer. Antes o intervalo devolvia antes de ler o relógio e o app pedia justamente
            // a hora que ela acabou de falar. Sem hora dita, continua pedindo — não inventamos.
            val clockHit = extractClock(rest)
            if (clockHit?.time != null) {
                return clockHit.copy(
                    remaining = TextNormalizer.compactSpaces(STARTS_AT.replace(clockHit.remaining, " ")),
                )
            }
            return TimeHit(
                null,
                rest,
                true,
                "“${m.value}” é um intervalo, não um horário do dia. Diga o horário da primeira dose.",
            )
        }

        // "das 14 às 16h": o compromisso é no INÍCIO da faixa. Sem isto o "14" (sem "h") não casava
        // e o "16h" virava a hora do alarme — o aviso tocava no fim, com cara de certeza.
        RANGE.find(remaining)?.let { m ->
            val startHour = hourFromToken(m.groupValues[1]) ?: return@let
            val endHour = hourFromToken(m.groupValues[2]) ?: return@let
            if (startHour !in 0..23 || endHour !in 0..23) return@let
            val rest = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
            // "das duas às quatro da tarde": o "da tarde" vale para as duas pontas, e sem ele a
            // hora do início sairia 02:00 em vez de 14:00.
            val period = PERIOD_PHRASE.find(m.value)?.value ?: ""
            val hour = applyPeriodHour(startHour, period)
            return TimeHit(
                LocalTime.of(hour % 24, 0),
                rest,
                ambiguous = period.isBlank() && startHour in 1..6,
            )
        }

        // "reunião das 8 amanhã": o "das" abre a hora sem o par "às 16h". Depois da faixa, para
        // não roubar o "das 14" da faixa antes de ela ser lida.
        DAS_CLOCK.find(remaining)?.let { m ->
            val raw = hourFromToken(m.groupValues[1]) ?: return@let
            if (raw !in 0..23) return@let
            val rest = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
            val period = PERIOD_PHRASE.find(m.value)?.value ?: ""
            return TimeHit(
                LocalTime.of(applyPeriodHour(raw, period) % 24, 0),
                rest,
                ambiguous = period.isBlank() && raw in 1..6,
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

        // O "em ponto" também qualifica estas duas horas, e o ramo do relógio comum já o consumia
        // (`consumirEmPonto`); estes dois retornavam antes disso, então o qualificador sobrava no
        // título: "meio-dia em ponto" virava a tarefa "Ponto". Vale para a palavra escrita, não só
        // para "12h" — a hora reconhecida é a mesma coisa, venha ela de número ou de nome.
        Regex("""\bmeio[-\s]?dia\b""").find(remaining)?.let { m ->
            val tail = trailingMinutes(remaining, m.range.last + 1)
            remaining = remaining.replace(m.value + (tail?.second ?: ""), " ")
            return TimeHit(LocalTime.of(12, tail?.first ?: 0), consumirEmPonto(remaining), false)
        }
        Regex("""\bmeia[-\s]?noite\b""").find(remaining)?.let { m ->
            val tail = trailingMinutes(remaining, m.range.last + 1)
            remaining = remaining.replace(m.value + (tail?.second ?: ""), " ")
            return TimeHit(LocalTime.of(0, tail?.first ?: 0), consumirEmPonto(remaining), false)
        }

        // "de 14 a 16", "entre 9 e 10": faixa sem o "h" — não há hora para cravar (o "a"/"e" não
        // diz se é 14h ou 16h), e os números saem do título para não deixar "14 16" pendurado nele.
        // O "entre" fica: sem o par, é a palavra que sobra na frase.
        BARE_RANGE_ENTRE.find(remaining)?.let { m ->
            remaining = remaining.replace(m.value, m.groupValues[1])
        }
        RANGE_BARE.find(remaining)?.let { m ->
            remaining = TextNormalizer.compactSpaces(remaining.replace(m.value, " "))
        }

        return extractClock(remaining) ?: TimeHit(null, remaining, false)
    }

    /**
     * O relógio da frase: dígito, por extenso e o "às N" sem "h". Extraído de `extractTime` porque
     * a faixa ("das 14 às 16h") precisa da mesma leitura depois de tirar a faixa do texto — duas
     * cópias divergiriam, e é justamente a hora do compromisso que não pode divergir.
     */
    private fun extractClock(text: String): TimeHit? {
        var remaining = text

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
        val note: String? = null,
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
            val rest = stripDayWords(hit.remaining)
            // A frase pode dizer as DUAS coisas: a data nomeada ("dia das mães") E um dia da semana
            // ("no sábado"). O `namedDate` devolve com `return` cedo e nunca olhava o dia dito — a
            // data saía DOMINGO com título 'Sábado almoço', completa e não-ambígua, e a caixa rápida
            // confirmava a contradição de um toque. A mesma doutrina do F1/F2: quando as duas
            // expressões DISCORDAM, o dia dito (a específica) manda E o rascunho escala. Quando
            // concordam ("sexta-feira santa" já sai como data nomeada, sem sobrar dia), nada muda.
            val diasDitos = extractWeekDays(rest)
            // A mesma regra do ramo relativo, e agora num lugar só (`dataEDiasDitosDiscordam`):
            // quando a data nomeada e os dias ditos DISCORDAM, o rascunho escala. Com DOIS dias
            // ditos nunca há "a" data — o guard antigo só olhava `size == 1`, então o gêmeo da
            // doutrina ficou de fora e a data nomeada saía completa e confirmável de um toque,
            // descartando os dois dias calados ("natal no sábado e domingo" → 25/12, uma sexta,
            // `qc=true`). A duplicação da regra foi o que deixou o caso de fora; a função única
            // fecha a classe para os dois ramos.
            if (dataEDiasDitosDiscordam(hit.date, diasDitos)) {
                // Com UM dia dito vale a primeira ocorrência dele (a expressão específica manda);
                // com DOIS não existe uma data que os honre, e a nomeada fica como referência.
                val dia = if (diasDitos.size == 1) {
                    RecurrenceEngine.firstOnOrAfter(
                        RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = diasDitos),
                        today,
                        today,
                    )
                } else {
                    hit.date
                }
                return DateHit(
                    dia,
                    stripWeekDays(rest),
                    true,
                    if (diasDitos.size == 1) {
                        "${NotasDoRascunho.DATA_AMBIGUA} a data nomeada e o dia da semana dito não caem no mesmo dia."
                    } else {
                        "${NotasDoRascunho.DATA_AMBIGUA} a data nomeada e os dias ditos não caem no mesmo dia."
                    },
                )
            }
            return DateHit(hit.date, rest, false)
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
            // "sexta daqui a dois dias": a conta crua (hoje + 2) dá SÁBADO, e o dia que ela disse
            // ficava sem quem o ouvisse — o rascunho saía completo e não-ambíguo, e a caixa rápida
            // confirmava o sábado em silêncio (P1-B da 3ª revisão do #55).
            //
            // O dia nomeado é a expressão específica e manda: vale a PRIMEIRA ocorrência dele a
            // partir de hoje, e não "a primeira depois da conta crua". A diferença aparece quando a
            // conta crua já passou do dia: quinta + 1 semana = quinta, e o "on-or-after" dava a sexta
            // da semana SEGUINTE, pulando a de amanhã.
            //
            // Mas o dia nomeado não pode vencer SOZINHO: "quinta daqui a duas semanas" com hoje
            // numa quinta caía em HOJE, descartando o "duas semanas" em silêncio (P1 deste lote).
            // Dois dias ditos com uma borda só não têm um dia que os satisfaça — a mesma regra do
            // `monthEdge`. Quando a conta crua e o dia nomeado DISCORDAM, vale o dia nomeado (a
            // expressão específica), mas o rascunho escala: cravar um dos dois calado é o defeito.
            val namedDay = extractWeekDays(remaining)
            if (namedDay.size == 1) {
                remaining = stripWeekDays(remaining)
                // A conta crua caindo NO dia dito: as duas expressões concordam, existe UMA data que
                // satisfaz as duas, e não há dúvida a escalar. Sem este ramo, "quinta daqui a duas
                // semanas" (com hoje numa quinta) escalava e devolvia a PRIMEIRA quinta — hoje —
                // descartando o "duas semanas" em silêncio. A pergunta "as duas expressões
                // discordam?" mora na função única (`dataEDiasDitosDiscordam`), que o ramo da data
                // nomeada também usa: a duplicação foi o que deixou o gêmeo de fora.
                if (!dataEDiasDitosDiscordam(date, namedDay)) {
                    return DateHit(date, remaining, false)
                }
                val onNamedDay = RecurrenceEngine.firstOnOrAfter(
                    RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = namedDay),
                    today,
                    today,
                )
                return DateHit(
                    onNamedDay ?: date,
                    remaining,
                    true,
                    "${NotasDoRascunho.DATA_AMBIGUA} “${m.value}” e o dia dito não caem no mesmo dia.",
                )
            }
            // Dois dias ditos com um deslocamento: com DOIS candidatos legítimos na frase, não existe
            // "a" data — o rascunho escala SEMPRE. A função responde por construção (nenhuma conta cai
            // em dois dias distintos), e chamá-la aqui mantém a regra num lugar só também neste ramo:
            // se a doutrina mudar, os três pontos acompanham.
            if (namedDay.size > 1) {
                return DateHit(
                    date,
                    remaining,
                    dataEDiasDitosDiscordam(date, namedDay),
                    "${NotasDoRascunho.DATA_AMBIGUA} “${m.value}” e os dias ditos não caem no mesmo dia.",
                )
            }
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
            remaining = remaining.replace(m.value, " ")
            if (month !in 1..12 || day !in 1..31) {
                return DateHit(null, remaining, true)
            }
            val year = when {
                yearRaw.isBlank() -> null
                yearRaw.length == 2 -> 2000 + yearRaw.toInt()
                else -> yearRaw.toInt()
            }
            return resolveDate(today, year, month, day, m.value, remaining)
        }

        // O "dia"/"no dia" antes do dia do mês faz parte da data, não do título: "dia 15 de
        // novembro missa" é a missa, não "Dia missa".
        val extenso = Regex(
            """\b(?:(?:no\s+)?dia\s+)?(\d{1,2})\s+de\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)(?:\s+de\s+(\d{4}))?\b""",
        )
        extenso.find(remaining)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = monthFromName(m.groupValues[2])
            val year = m.groupValues[3].ifBlank { null }?.toInt()
            remaining = remaining.replace(m.value, " ")
            return resolveDate(today, year, month, day, m.value, remaining)
        }

        // "dois de maio": o dia por extenso. A regex acima exige dígito, então a frase ficava sem
        // data e o "dois de maio" sobrava no título.
        val extensoPalavra = Regex(
            """\b(?:(?:no\s+)?dia\s+)?($WORD_DAY_ALT)\s+de\s+(janeiro|fevereiro|marco|abril|maio|junho|julho|agosto|setembro|outubro|novembro|dezembro)(?:\s+de\s+(\d{4}))?\b""",
        )
        extensoPalavra.find(remaining)?.let { m ->
            val day = WORD_DAYS[TextNormalizer.compactSpaces(m.groupValues[1])] ?: return@let
            val month = monthFromName(m.groupValues[2])
            val year = m.groupValues[3].ifBlank { null }?.toInt()
            remaining = remaining.replace(m.value, " ")
            return resolveDate(today, year, month, day, m.value, remaining)
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
            // O mês aqui é deduzido, e o mês deduzido pode não ter o dia dito: "no dia 31" ouvido
            // em fevereiro não é 28/02. O `clampToValidDate` arredondava para o último dia do mês
            // com `ambiguous = false` e a caixa rápida confirmava a data que ela não falou. É a
            // mesma recusa do `resolveDate`, no terceiro caminho de data avulsa deste método.
            val date = try {
                LocalDate.of(year, month, day)
            } catch (_: DateTimeException) {
                val mes = Month.of(month).getDisplayName(TextStyle.FULL, locale)
                return DateHit(
                    null,
                    remaining,
                    true,
                    "${NotasDoRascunho.DATA_IMPOSSIVEL}: o dia $day não existe em $mes. Confirme a data.",
                )
            }
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

    /**
     * A data que o texto aponta, com o ano que ela não disse resolvido aqui.
     *
     * O ano ausente é um palpite, e um palpite não pode virar certeza calada: "reunião 05/08"
     * dita em 20/08/2026 rolava para 05/08/2027 com `ambiguous = false` e a caixa rápida
     * confirmava quase um ano à frente sem avisar. A data continua a mais próxima no futuro —
     * rolar para o ano seguinte é a leitura certa, e é o que mantém "25/12" no Natal deste ano —
     * mas agora ela chega ambígua, para a tela confirmar em vez de a caixa rápida decidir.
     *
     * E data que não existe é recusa, não arredondamento: "31/02" virava 28/02 com cara de
     * certeza. `RecurrenceEngine.clampToValidDate` continua valendo para as regras que repetem
     * ("todo dia 31"), onde o ajuste é a semântica; aqui, na data avulsa, o dia não existe e o
     * rascunho fica sem data.
     */
    private fun resolveDate(
        today: LocalDate,
        year: Int?,
        month: Int,
        day: Int,
        raw: String,
        remaining: String,
    ): DateHit {
        if (year != null) {
            val date = try {
                LocalDate.of(year, month, day)
            } catch (_: DateTimeException) {
                return DateHit(
                    null,
                    remaining,
                    true,
                    "${NotasDoRascunho.DATA_IMPOSSIVEL}: “$raw” não existe no calendário.",
                )
            }
            return DateHit(date, remaining, false)
        }
        val thisYear = try {
            LocalDate.of(today.year, month, day)
        } catch (_: DateTimeException) {
            null
        }
        if (thisYear != null && !thisYear.isBefore(today)) {
            return DateHit(thisYear, remaining, false)
        }
        // O próximo ano em que a data existe — 29/02 não vale em ano comum, e pular para o
        // bissexto seguinte é a data que ela disse, não o 28/02 que nunca foi dito.
        val nextYear = (today.year + 1..today.year + 8).firstOrNull { y ->
            try {
                LocalDate.of(y, month, day)
                true
            } catch (_: DateTimeException) {
                false
            }
        } ?: return DateHit(
            null,
            remaining,
            true,
            "${NotasDoRascunho.DATA_IMPOSSIVEL}: “$raw” não existe no calendário.",
        )
        val note = if (thisYear == null) {
            "${NotasDoRascunho.DATA_A_CONFIRMAR}: “$raw” não existe este ano; ficou em $nextYear."
        } else {
            "${NotasDoRascunho.DATA_A_CONFIRMAR}: “$raw” já passou este ano; ficou em $nextYear."
        }
        return DateHit(LocalDate.of(nextYear, month, day), remaining, true, note)
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
        // O filtro apaga o que tem cara de hora ("8h", "8h30", "8:30") e o dígito que a frase já
        // usou como dia, hora ou recorrência — mas preserva a quantidade que ela contou ("comprar
        // 2 caixas"), que o filtro antigo comia junto. O contexto do dígito não sobrevive no
        // `remaining` ("todo dia 5 caminhar" sobra "5 caminhar"), então a leitura é sobre a frase
        // original. As palavras de hora por extenso ("oito") continuam saindo quando sozinhas.
        val words = TextNormalizer.compactSpaces(remaining).split(" ")
        val dataEHora = dateTimeDigitPositions(original)
        // A pontuação do token vive nos dois lados do casamento: o `folded` já vinha limpo, mas
        // aqui o `leftover` guardava a palavra com a vírgula colada ("pão,"). Nenhum casamento, e o
        // item da lista sumia do título em silêncio ("comprar pão, leite" virava "Comprar leite",
        // `ambiguous=false`, sem nota). Limpar o `leftover` na MESMA medida do `folded` faz a lista
        // falada por vírgula sobreviver inteira.
        val leftover = words.filterIndexed { index, word ->
            word.isNotBlank() &&
                (word !in FILLERS || (word == "meia" && words.getOrNull(index + 1) == "duzia")) &&
                !word.matches(Regex("\\d{1,2}h(?:\\d{1,2}|oras?)?|\\d{1,2}:\\d{2}"))
        }.map { it.trim(',', '.', '!', '?') }.toMutableList()
        // O dígito que a frase usou como data, hora ou recorrência sai AQUI, pela posição na frase
        // original — é a única em que o contexto ("dia 5", "8 da manhã") ainda existe, e a posição
        // não se confunde com a da quantidade quando as duas são o mesmo número ("2 caixas às 2h").
        val rebuilt = original.split(Regex("\\s+")).filterIndexed { index, word ->
            if (index in dataEHora) return@filterIndexed false
            val folded = TextNormalizer.fold(word).trim(',', '.', '!', '?')
            val idx = leftover.indexOfFirst { it == folded || folded.startsWith(it) }
            if (idx >= 0) {
                leftover.removeAt(idx)
                true
            } else {
                false
            }
        }.map { it.trim(',', '.', '!', '?') }
        val title = rebuilt.joinToString(" ").trim().trim(',', '.', '!')
        if (title.isNotBlank()) {
            return title.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
        return ""
    }

    /**
     * A doutrina deste lote, num lugar só: as duas expressões de data DISCORDAM quando a data já
     * resolvida (a conta crua do relativo, ou a data nomeada) não é nenhum dos dias ditos. Nesse
     * caso o rascunho escala em vez de cravar um dos dois calado — o defeito é escolher calado.
     *
     * Com DOIS dias ditos distintos a resposta é sempre `true`: nenhuma data cai em dois dias da
     * semana diferentes. É o gêmeo que ficou de fora quando a regra estava duplicada no ramo do
     * relativo (só `size == 1`): a data nomeada devolvia a data completa e confirmável de um toque,
     * descartando os dois dias calados.
     */
    private fun dataEDiasDitosDiscordam(date: LocalDate?, diasDitos: Set<DayOfWeek>): Boolean {
        if (date == null || diasDitos.isEmpty()) return false
        if (diasDitos.size > 1) return true
        return date.dayOfWeek !in diasDitos
    }

    /**
     * As posições na frase ORIGINAL (por palavra) dos dígitos que ela usa como data, hora ou
     * recorrência — "dia 5", "8 da manhã", "12 em ponto", "20h".
     *
     * A identidade é a POSIÇÃO, não o valor: "comprar 2 caixas às 2h" tem o mesmo "2" nos dois
     * papéis, e um contador por valor gastava a única unidade no "2h" e comia o "2 caixas" junto.
     * O resultado mudava só por causa do "h" ("comprar 2 caixas às 2" preservava) — a mesma frase,
     * dois títulos. Por posição, o "h" não muda mais nada.
     */
    private fun dateTimeDigitPositions(original: String): MutableSet<Int> {
        val folded = TextNormalizer.fold(original)
        val positions = mutableSetOf<Int>()
        DATE_TIME_DIGIT.findAll(folded).forEach { m ->
            val group = (1..m.groups.size - 1).firstOrNull { g ->
                m.groups[g]?.value?.isNotBlank() == true
            } ?: return@forEach
            positions += palavraDoIndice(folded, m.groups[group]!!.range.first)
        }
        return positions
    }

    /** O índice da palavra (separada por espaço) que contém a posição [index] do texto dobrado. */
    private fun palavraDoIndice(text: String, index: Int): Int {
        var palavra = 0
        for (i in 0 until index) {
            if (text[i].isWhitespace() && (i == 0 || !text[i - 1].isWhitespace())) palavra++
        }
        return palavra
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

        /**
         * "das 14 às 16h": faixa de horário. O grupo 1 é a primeira hora, o 2 a última. O "h" no
         * fim da segunda é o que a distingue de "entre 9 e 10"/"de 14 a 16", que seguem sem hora
         * reconhecida — o alvo é a faixa que ela fala com "das ... às ...h".
         */
        private val RANGE = Regex(
            """\b(?:(?:das|de)\s+|\bas\s+)(\d{1,2}|[a-z]+(?:\s+e\s+[a-z]+)?)(?:\s*h(?:oras?)?)?""" +
                """\s+(?:as|ate)\s+(\d{1,2}|[a-z]+(?:\s+e\s+[a-z]+)?)(?:\s*h(?:oras?)?)?""" +
                """(?:\s+(?:a|da|de|na)\s+(manha|tarde|noite|madrugada))?\b""",
        )

        /** "reunião das 8 amanhã": o "das" abre a hora mesmo sem o "h" e sem o par "às". */
        private val DAS_CLOCK = Regex("""\bdas\s+(\d{1,2}|[a-z]+(?:\s+e\s+[a-z]+)?)\b""")

        /**
         * "de 14 a 16", "entre 9 e 10": faixa sem o "h". Não vira hora — "de 14 a 16" não diz se o
         * compromisso é às 14h ou às 16h, e cravar uma das duas seria o mesmo defeito de antes, ao
         * contrário. Sai do texto para o título não carregar "14 16".
         */
        private val RANGE_BARE = Regex(
            """\b(?:(?:das|de)\s+|\bas\s+)(\d{1,2})\s+(?:a|ate|as)\s+(\d{1,2})\b""",
        )

        /** "entre 9 e 10": só os números saem; o "entre" é a palavra que sobra na frase. */
        private val BARE_RANGE_ENTRE = Regex("""\b(entre)\s+\d{1,2}\s+e\s+\d{1,2}\b""")

        /** A hora da faixa em dígito ("14") ou por extenso ("duas"). */
        private fun hourFromToken(raw: String): Int? =
            raw.toIntOrNull() ?: WORD_HOURS[TextNormalizer.compactSpaces(raw)]

        /** "começando amanhã às 8h": a primeira dose do intervalo foi dita. */
        private val STARTS_AT = Regex("""\bcomec(?:ando|a|ar)\b""")

        /** O valor falado, palavra a palavra: "cento e vinte e cinco" = 125. */
        private val NUMBER_WORDS = mapOf(
            // "meio" vale zero no léxico: o que ele multiplica é a escala ("meio milhão"), lida em
            // [MEIO_MIL_ESCALA]. Sem a entrada, o "meio" sobraria no título.
            "meio" to 0,
            "um" to 1, "uma" to 1,
            "dois" to 2, "duas" to 2,
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
            "trinta" to 30,
            "quarenta" to 40,
            "cinquenta" to 50,
            "sessenta" to 60,
            "setenta" to 70,
            "oitenta" to 80,
            "noventa" to 90,
            "duzentos" to 200, "duzentas" to 200,
            "trezentos" to 300, "trezentas" to 300,
            "quatrocentos" to 400, "quatrocentas" to 400,
            "quinhentos" to 500, "quinhentas" to 500,
            "seiscentos" to 600, "seiscentas" to 600,
            "setecentos" to 700, "setecentas" to 700,
            "oitocentos" to 800, "oitocentas" to 800,
            "novecentos" to 900, "novecentas" to 900,
        )

        /**
         * As palavras que formam um valor falado, da maior para a menor — "quatrocentos" antes de
         * "quatro", senão a alternância pararia no prefixo. "milhão" entra aqui, e não só no motor:
         * sem ele o casamento de "um milhão e duzentos mil reais" começava em "duzentos".
         */
        private val NUMBER_WORD_ALT: String =
            (NUMBER_WORDS.keys + listOf("cem", "cento", "mil", "milhao", "milhoes"))
                .distinct()
                .sortedByDescending { it.length }
                .joinToString("|") { """\b$it\b""" }

        /**
         * O número do valor em pt-BR: "120", "30,50", "1.500", "1.234,56". O milhar por ponto vem
         * primeiro porque é o mais específico — sem ele, "15.000 reais" era lido como "000" (a
         * tarefa entrava com 0 centavos) e "R$ 1.234,56" como "1.23" (R$1,23).
         *
         * As bordas (`(?<![\d.,])` / `(?![\d.,])`) prendem o número inteiro: sem elas, "30.50 reais"
         * casava só o "50" (o "30." era descartado) e virava R$50,00 — o re-ancorar no pedaço do
         * número, que é o defeito que este valor não pode ter.
         */
        private const val BR_NUMBER =
            """(?<![\d.,])(?:\d{1,3}(?:\.\d{3})+(?:,\d{1,2})?|\d+(?:,\d{1,2})?)(?![\d.,])"""

        /**
         * "120 reais", "R$ 30", "rs 30": o valor em dígito. O "R$" (e o "rs" que o reconhecimento
         * costuma devolver) sozinho já é dinheiro — sem o segundo grupo, "pagar R$ 30" ficava com o
         * "R$" pendurado no título e sem valor nenhum.
         *
         * O grupo 2/4/6 é a escala opcional ("15 mil", "1,5 milhão"): "quinze mil reais" sem ela
         * casaria só o "quinze". "meio milhão" tem ramo próprio ([MEIO_MILHAO]).
         */
        private val REAIS_NUMERIC = Regex(
            """\b($BR_NUMBER)\s*(?:(mil|milhao|milhoes)\s+(?:de\s+)?)?(?:reais|real)\b""" +
                """|\br\s*\$\s*($BR_NUMBER)\s*(?:(mil|milhao|milhoes)\s+(?:de\s+)?)?(?:reais|real)?\b""" +
                """|\brs\s+($BR_NUMBER)\s*(?:(mil|milhao|milhoes)\s+(?:de\s+)?)?(?:reais|real)?\b""",
        )

        /**
         * "cento e vinte reais", "mil e duzentos reais", "dois mil e quinhentos reais": o valor por
         * extenso. O conector "e" é opcional entre as palavras — "dois mil" é justaposto na fala e,
         * exigindo o "e", o motor re-ancorava em "mil e quinhentos" e gravava 1500 em vez de 2500.
         *
         * O "de" opcional cobre "um milhão de reais"; "contos" saiu (ver [MEIO_REAL]): a leitura do
         * conto antigo (mil réis) e a coloquial (um real) não cabem na mesma frase sem inventar.
         */
        private val REAIS_EXTENSO = Regex(
            """\b((?:$NUMBER_WORD_ALT)\b(?:\s+(?:e\s+)?(?:$NUMBER_WORD_ALT)\b)*)\s+(?:de\s+)?(?:reais|real)\b""",
        )

        /**
         * "meio milhão de reais" (500 mil) e "meio mil reais" (500): o "meio" é o multiplicador da
         * escala, e fica fora da alternância de palavras (senão casaria "meio real" = 50 centavos).
         * Vem antes do extenso, que sem ele casaria só o "milhão"/"mil" e gravaria o dobro.
         */
        private val MEIO_MIL_ESCALA = Regex("""\bmeio\s+(mil|milhao|milhoes)\b(?:\s+de)?\s*(?:reais|real)?\b""")

        /**
         * A unidade de dinheiro dita: a palavra "reais"/"real" ou o cifrão "R$"/"rs". É o que
         * separa "5 mil reais" de "caminhar 5 mil passos" — sem ela, nenhum valor é interpretado.
         */
        private val REAIS_TAIL = Regex("""\b(?:reais|real)\b|r\s*\$|rs\b""")

        /** Até onde a unidade pode estar do escalar ("meio milhão de reais", com o "de" no meio). */
        private const val UNIDADE_ALCANCE = 16

        /** O inteiro em dígito sem separador vale até aqui; com 5+ pode ser milhar malformado. */
        private const val MAX_DIGITOS_INTEIRO = 4

        /**
         * O maior valor em reais que ainda cabe no `Long` em centavos — o teto é o do TIPO, e não
         * uma contagem de dígitos: "92.233.720.368.547.758 reais" são 9.223.372.036.854.775.800
         * centavos, e um centavo de real a mais estoura. Contar dígitos recusava valores que cabiam
         * e aceitava outros que estouravam, com o mesmo número dando desfechos opostos só por causa
         * do separador de milhar.
         */
        private val MAX_REAIS_INTERPRETAVEIS = BigDecimal(Long.MAX_VALUE).movePointLeft(2)

        /** O "e <número>" logo depois do valor: centavos que ela não nomeou (ver `conectorSemCentavos`). */
        private val CONECTOR_NUMERO = Regex("""\s+e\s+(?:$NUMBER_WORD_ALT)\b|\s+e\s+\d{1,2}\b""")

        /** "e cinquenta centavos": os centavos falados depois do valor em reais. */
        private val CENTAVOS = Regex(
            """\s+e\s+((?:$NUMBER_WORD_ALT)\b(?:\s+(?:e\s+)?(?:$NUMBER_WORD_ALT)\b)*|\d{1,2})\s+centavos?\b""",
        )

        /** "meio real": cinquenta centavos — a única fração falada com o nome do dinheiro. */
        private val MEIO_REAL = Regex("""\bmeio\s+(?:real|contos?)\b""")

        /**
         * Onde o dígito é data, hora ou recorrência, e não uma quantidade: "dia 5", "semana 5",
         * "8 da manhã", "12 em ponto", "20h", "25/12". Casa na frase ORIGINAL — o contexto já foi
         * comido do `remaining` quando o título é montado.
         */
        private val DATE_TIME_DIGIT = Regex(
            """\b(\d{1,2})\s*h(?:oras?)?\b""" +
                """|\b(?:dias?|semanas?|mes(?:es)?|anos?)\s+(\d{1,2})\b""" +
                """|\b(\d{1,2})\s+em\s+ponto\b""" +
                """|\b(\d{1,2})\s+(?:da|de|do|na)\s+(?:manha|tarde|noite|madrugada)\b""" +
                """|\b(\d{1,2})[/-](\d{1,2})\b""",
        )

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
         * A forma com ESPAÇO consome o " feira" INCONDICIONALMENTE — igual à forma com hífen. O guard
         * de substantivo que existia aqui (`FEIRA_NOUN`, poupando o "feira" seguido de
         * `de|do|da|dos|das`) partia de uma premissa falsa: `"de"` é justamente como se encadeia a
         * TAREFA depois do dia ("sexta feira de natação"), não só o complemento nominal ("feira de
         * ciências"). Como o dia com espaço então consumia só "sexta", o " feira" sobrava órfão no
         * início do resto e o guard o poupava: `"<dia> feira de <tarefa>"` gravava "Feira natação"
         * (75/75 casos; o `main` dá 0/75). "feira" após um dia da semana ditado com espaço é SEMPRE
         * ruído de fala — nos dois casos ele sai do título, e a ambiguidade do "de" não existe.
         *
         * O substantivo que NÃO segue um dia da semana continua poupado em `stripFeiraSuffix`
         * ("na feira do bairro", "sábado feira"): ali o guard é o que olha a preposição antes, e não
         * uma lista de determinantes depois.
         */
        private const val FEIRA_NOUN_TAIL_SRC = """(?:de|do|da|dos|das)\b"""
        private val FEIRA_NOUN_TAIL = Regex("""^\s+$FEIRA_NOUN_TAIL_SRC""")
        private val WEEKDAY_PATTERNS = listOf(
            // "sábado"/"domingo" NÃO levam o sufixo "-feira" (só segunda a sexta): o "feira" depois
            // deles é o mercado, e consumi-lo apagava o nome da tarefa (P2-3).
            Regex("""\bdomingos?(?:-?feira)?\b""") to DayOfWeek.SUNDAY,
            Regex("""\bsegundas?(?:-?feira|\s+feira)?\b""") to DayOfWeek.MONDAY,
            Regex("""\btercas?(?:-?feira|\s+feira)?\b""") to DayOfWeek.TUESDAY,
            Regex("""\bquartas?(?:-?feira|\s+feira)?\b""") to DayOfWeek.WEDNESDAY,
            Regex("""\bquintas?(?:-?feira|\s+feira)?\b""") to DayOfWeek.THURSDAY,
            Regex("""\bsextas?(?:-?feira|\s+feira)?\b""") to DayOfWeek.FRIDAY,
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

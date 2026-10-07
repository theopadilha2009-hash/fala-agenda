package com.theopadilha.falaagenda.domain.recurrence

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.reminder.DraftSchedule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * O oráculo de faixa: o motor de recorrência não pode estourar nem inventar data para
 * `dayOfMonth`/`monthOfYear` fora do calendário, **seja qual for a origem**.
 *
 * O defeito medido que este teste prende: a IA devolvia `month_of_year = 13` (o schema em
 * `supabase/functions/_shared/openai.ts` declara os dois campos como `integer` sem
 * `minimum`/`maximum`), o rascunho virava `RecurrenceRule(YEARLY, monthOfYear = 13)`, a caixa
 * de confirmação rápida chamava `DraftSchedule.firstOccurrence` **na composição** e o
 * `YearMonth.of(2026, 13)` derrubava a home — sem error boundary e sem tela de confirmação,
 * porque `canQuickConfirm` não olha a recorrência.
 *
 * Não é um exemplo solto: cruza os dois eixos (meses `-1, 0, 1..12, 13, 99` × dias `0..32`)
 * com os seis `RecurrenceKind` e **conta as violações** em vez de afirmar um caso. O projeto
 * já provou que suíte verde não pega o que importa; o que pega é o oráculo que conta.
 */
class RecurrenceEngineFaixaInvalidaTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val start = LocalDate.of(2026, 8, 20)
    private val now = Instant.parse("2026-08-20T13:00:00Z")

    private val meses = listOf(-1, 0) + (1..12).toList() + listOf(13, 99)
    private val dias = (0..32).toList()
    private val kinds = RecurrenceKind.entries.toList()

    private fun regra(kind: RecurrenceKind, mes: Int, dia: Int) = RecurrenceRule(
        kind = kind,
        weekDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        dayOfMonth = dia,
        monthOfYear = mes,
    )

    /**
     * A data devolvida é uma resposta aceitável para esta regra, a partir de [from]?
     *
     * Nulo = sem violação. `LocalDate` já é válido por construção, então "data inválida" aqui
     * quer dizer: anterior ao piso, rolada além do razoável, num mês/dia que a regra não pediu,
     * ou num dia da semana que a regra não contempla.
     */
    private fun violacao(
        label: String,
        rule: RecurrenceRule,
        from: LocalDate,
        date: LocalDate,
    ): String? {
        if (date.isBefore(from)) return "$label: devolveu $date, antes do piso $from"
        // `upcoming(limit = 3)` numa regra anual atravessa três anos por construção; nas demais
        // a terceira ocorrência cabe em dois. Estourar o teto aqui é o "arredondou para sempre".
        val anosMaximos = if (rule.kind == RecurrenceKind.YEARLY) 3 else 2
        if (date.year > from.year + anosMaximos) return "$label: rolou até $date, a partir de $from"
        when (rule.kind) {
            RecurrenceKind.YEARLY -> {
                val mes = rule.monthOfYear
                if (mes != null && mes in 1..12 && date.monthValue != mes) {
                    return "$label: devolveu mês ${date.monthValue} para o mês $mes"
                }
            }
            RecurrenceKind.MONTHLY -> Unit
            RecurrenceKind.WEEKLY -> {
                if (rule.weekDays.isNotEmpty() && date.dayOfWeek !in rule.weekDays) {
                    return "$label: devolveu ${date.dayOfWeek}, fora de ${rule.weekDays}"
                }
            }
            RecurrenceKind.WEEKDAYS -> {
                if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
                    return "$label: devolveu ${date.dayOfWeek} numa regra de dias úteis"
                }
            }
            RecurrenceKind.NONE, RecurrenceKind.DAILY -> Unit
        }
        // O dia pedido só manda nas regras que o consultam; em DAILY/WEEKLY/WEEKDAYS ele é
        // ruído do rascunho e cobrar coerência ali seria falso positivo do oráculo.
        val pedido = rule.dayOfMonth
        val diaManda = rule.kind == RecurrenceKind.MONTHLY || rule.kind == RecurrenceKind.YEARLY
        if (diaManda && pedido != null && pedido in 1..31 && date.dayOfMonth > pedido) {
            return "$label: inventou o dia ${date.dayOfMonth} para o dia pedido $pedido"
        }
        return null
    }

    @Test
    fun nenhumaCombinacaoDeMesEDiaEstouraNemInventaData() {
        val violacoes = mutableListOf<String>()
        var combinacoes = 0
        for (kind in kinds) {
            for (mes in meses) {
                for (dia in dias) {
                    combinacoes++
                    val rule = regra(kind, mes, dia)
                    val label = "$kind mês=$mes dia=$dia"
                    try {
                        RecurrenceEngine.firstOnOrAfter(rule, start, start)?.let { date ->
                            violacao("$label firstOnOrAfter", rule, start, date)?.let { violacoes += it }
                        }
                        RecurrenceEngine.upcoming(rule, start, start, 3).forEach { date ->
                            violacao("$label upcoming", rule, start, date)?.let { violacoes += it }
                        }
                        RecurrenceEngine.nextAfter(rule, start, start)?.let { date ->
                            violacao("$label nextAfter", rule, start, date)?.let { violacoes += it }
                        }
                        DraftSchedule.firstOccurrence(
                            rule = rule,
                            chosenDate = start,
                            chosenTime = LocalTime.of(9, 0),
                            zoneId = zone,
                            now = now,
                        ).date.let { date ->
                            violacao("$label DraftSchedule", rule, start, date)?.let { violacoes += it }
                        }
                    } catch (t: Throwable) {
                        violacoes += "$label estourou ${t::class.simpleName}: ${t.message}"
                    }
                }
            }
        }
        println("RECORRENCIA_FAIXA combinacoes=$combinacoes violacoes=${violacoes.size}")
        assertThat(violacoes).isEmpty()
        assertThat(combinacoes).isEqualTo(kinds.size * meses.size * dias.size)
    }

    /**
     * A regra que não repete continua nascendo com a data escolhida — o clamp não pode ter
     * mudado o caminho normal, só o caminho que estourava.
     */
    @Test
    fun faixaValidaContinuaProduzindoAsMesmasDatas() {
        assertThat(RecurrenceEngine.yearlyDate(2026, 2, 29)).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(RecurrenceEngine.yearlyDate(2028, 2, 29)).isEqualTo(LocalDate.of(2028, 2, 29))
        assertThat(RecurrenceEngine.clampToValidDate(2026, 2, 31)).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(RecurrenceEngine.clampToValidDate(2026, 4, 31)).isEqualTo(LocalDate.of(2026, 4, 30))
        assertThat(RecurrenceEngine.clampToValidDate(2026, 12, 31)).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(RecurrenceEngine.clampToValidDate(2026, 1, 1)).isEqualTo(LocalDate.of(2026, 1, 1))
    }
}

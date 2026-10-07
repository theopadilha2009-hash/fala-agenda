package com.theopadilha.falaagenda.domain.model

import com.theopadilha.falaagenda.domain.reminder.DraftSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class RecurrenceKind {
    NONE,
    DAILY,
    WEEKDAYS,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

data class RecurrenceRule(
    val kind: RecurrenceKind = RecurrenceKind.NONE,
    val weekDays: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
    val monthOfYear: Int? = null,
) {
    val isRecurring: Boolean get() = kind != RecurrenceKind.NONE

    /** O dia que a regra descreve: só existe de 1 a 31. Fora disso é dado de fora, não um pedido. */
    private val diaUtilizavel: Int? get() = dayOfMonth?.takeIf { it in 1..31 }

    /** O mês que a regra descreve: só existe de 1 a 12. */
    private val mesUtilizavel: Int? get() = monthOfYear?.takeIf { it in 1..12 }

    /**
     * A regra tem os campos que a sua descrição precisa?
     *
     * `MONTHLY` sem dia e `YEARLY` sem mês ou sem dia **não são recorrências** — são metade de
     * uma, e a metade que falta é justamente a que a frase da tela precisa dizer. Quem pergunta
     * isto é a fronteira da IA (`SupabaseFunctions.toDraft`), para rebaixar a regra a `NONE` em
     * vez de deixá-la chegar à tela como texto quebrado.
     *
     * A pergunta é uma só e mora aqui, e não escrita de novo em cada lugar que a consulta: a
     * versão anterior tinha a condição do `YEARLY` copiada na fronteira, e o `MONTHLY` ficou de
     * fora dela — o mesmo defeito, por outro `kind`.
     */
    val isCoherent: Boolean
        get() = when (kind) {
            RecurrenceKind.MONTHLY -> diaUtilizavel != null
            RecurrenceKind.YEARLY -> diaUtilizavel != null && mesUtilizavel != null
            else -> true
        }

    /**
     * A descrição é a **única origem do `"?"`** que aparecia na tela dela — e por isso a
     * invariante mora aqui, no ponto que renderiza, e não em quem produz a regra.
     *
     * Dois caminhos provaram isso: a regra anual sem mês (`"Todo 5 de ?"`) e a mensal sem dia
     * (`"Todo dia ? do mês"`), a segunda conformante ao schema do LLM — `day_of_month` é
     * `["integer","null"]` e `minimum`/`maximum` não mordem em `null`, então nem é preciso o
     * modelo alucinar. Um tipo que tornasse o estado inconstruível fecharia o caminho de
     * entrada, mas **não** o dado já gravado: `SeriesEntity.toDomain()` lê `recurrenceKind`/
     * `dayOfMonth` do banco sem validar, e uma linha antiga com `MONTHLY` sem dia continua
     * chegando aqui. Só um guard no renderizador fecha os dois.
     *
     * A regra incoerente cai na descrição da **cadência**, sem inventar o campo que falta: a
     * série realmente repete todo mês (o motor usa o dia do início da série, ver
     * `RecurrenceEngine.firstOnOrAfter`), então dizer "Sem repetição" seria a mentira oposta.
     */
    fun describePtBr(): String = when (kind) {
        RecurrenceKind.NONE -> "Única"
        RecurrenceKind.DAILY -> "Todos os dias"
        RecurrenceKind.WEEKDAYS -> "Dias úteis"
        RecurrenceKind.WEEKLY -> {
            val names = weekDays.sortedBy { it.value }.joinToString(" e ") { it.toPtBr() }
            if (names.isBlank()) "Semanal" else "Toda $names"
        }
        RecurrenceKind.MONTHLY -> diaUtilizavel?.let { "Todo dia $it do mês" } ?: "Todo mês"
        RecurrenceKind.YEARLY -> {
            val dia = diaUtilizavel
            val mes = mesUtilizavel?.toMonthPtBr()
            if (dia == null || mes == null) "Todo ano" else "Todo $dia de $mes"
        }
    }
}

enum class OccurrenceStatus {
    PENDING,
    COMPLETED,
    MISSED,
    CANCELLED,
}

enum class MissingDraftField {
    TITLE,
    DATE,
    TIME,
}

data class ParsedTaskDraft(
    val title: String,
    val localDate: LocalDate?,
    val localTime: LocalTime?,
    val recurrence: RecurrenceRule = RecurrenceRule(),
    val confidence: Double,
    val missingFields: Set<MissingDraftField>,
    val ambiguous: Boolean,
    val transcript: String,
    val notes: List<String> = emptyList(),
    val source: DraftSource = DraftSource.LOCAL,
    val amountCents: Long? = null,
    val observation: String = "",
) {
    val isComplete: Boolean
        get() = missingFields.isEmpty() && title.isNotBlank() && localDate != null && localTime != null

    fun canQuickConfirm(now: Instant, zone: ZoneId): Boolean {
        if (!isComplete || ambiguous) return false
        val date = localDate ?: return false
        val time = localTime ?: return false
        val at = date.atTime(time).atZone(zone).toInstant()
        // A escolha que nasce sem aviso não passa pela caixa rápida: é o mesmo predicado do
        // repositório e da tela (`DraftSchedule.bornWithoutReminder`), não uma quarta conta
        // da mesma regra. Aqui a data é literal, e para a regra que não repete — a única em
        // que o predicado morde — literal e primeira ocorrência são a mesma data.
        return !DraftSchedule.bornWithoutReminder(at, recurrence, now)
    }

    fun withManual(
        title: String = this.title,
        localDate: LocalDate? = this.localDate,
        localTime: LocalTime? = this.localTime,
        recurrence: RecurrenceRule = this.recurrence,
        amountCents: Long? = this.amountCents,
        observation: String = this.observation,
    ): ParsedTaskDraft {
        val missing = buildSet {
            if (title.isBlank()) add(MissingDraftField.TITLE)
            if (localDate == null) add(MissingDraftField.DATE)
            if (localTime == null) add(MissingDraftField.TIME)
        }
        return copy(
            title = title.trim(),
            localDate = localDate,
            localTime = localTime,
            recurrence = recurrence,
            amountCents = amountCents,
            observation = observation.trim(),
            missingFields = missing,
            ambiguous = false,
            confidence = if (missing.isEmpty()) 1.0 else 0.4,
            source = DraftSource.MANUAL,
        )
    }
}

enum class DraftSource { LOCAL, AI, MANUAL }

data class TaskSeries(
    val id: String,
    val title: String,
    val zoneId: ZoneId,
    val localTime: LocalTime,
    val startLocalDate: LocalDate,
    val recurrence: RecurrenceRule,
    val amountCents: Long? = null,
    val observation: String = "",
    /**
     * Datas que o usuário excluiu desta série: a ocorrência não deve voltar a nascer nelas.
     * Encerrar a série é `endedAt`, não entra aqui.
     */
    val skippedDates: Set<LocalDate> = emptySet(),
    val endedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isEnded: Boolean get() = endedAt != null

    fun isSkipped(localDate: LocalDate): Boolean = localDate in skippedDates
}

data class TaskOccurrence(
    val id: String,
    val seriesId: String,
    val localDate: LocalDate,
    val scheduledAt: Instant,
    val status: OccurrenceStatus,
    val completedAt: Instant? = null,
    val missedAt: Instant? = null,
    val reminderStep: Int = 0,
    val nextReminderAt: Instant? = null,
    val lastReminderAt: Instant? = null,
    val snoozedUntil: Instant? = null,
    val inexactAlarm: Boolean = false,
)

data class QuietHours(
    val start: LocalTime = LocalTime.of(22, 0),
    val end: LocalTime = LocalTime.of(8, 0),
)

data class AiActivationState(
    val activated: Boolean,
    val supabaseConfigured: Boolean,
    val lastError: String? = null,
)

fun DayOfWeek.toPtBr(): String = when (this) {
    DayOfWeek.MONDAY -> "segunda"
    DayOfWeek.TUESDAY -> "terça"
    DayOfWeek.WEDNESDAY -> "quarta"
    DayOfWeek.THURSDAY -> "quinta"
    DayOfWeek.FRIDAY -> "sexta"
    DayOfWeek.SATURDAY -> "sábado"
    DayOfWeek.SUNDAY -> "domingo"
}

fun DayOfWeek.toPtBrShort(): String = when (this) {
    DayOfWeek.MONDAY -> "Seg"
    DayOfWeek.TUESDAY -> "Ter"
    DayOfWeek.WEDNESDAY -> "Qua"
    DayOfWeek.THURSDAY -> "Qui"
    DayOfWeek.FRIDAY -> "Sex"
    DayOfWeek.SATURDAY -> "Sáb"
    DayOfWeek.SUNDAY -> "Dom"
}

fun Int.toMonthPtBr(): String = when (this) {
    1 -> "janeiro"
    2 -> "fevereiro"
    3 -> "março"
    4 -> "abril"
    5 -> "maio"
    6 -> "junho"
    7 -> "julho"
    8 -> "agosto"
    9 -> "setembro"
    10 -> "outubro"
    11 -> "novembro"
    12 -> "dezembro"
    else -> "?"
}

object OccurrenceIds {
    fun of(seriesId: String, localDate: LocalDate): String = "$seriesId:$localDate"
}

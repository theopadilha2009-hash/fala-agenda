package com.theopadilha.falaagenda.domain.model

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

    fun describePtBr(): String = when (kind) {
        RecurrenceKind.NONE -> "Única"
        RecurrenceKind.DAILY -> "Todos os dias"
        RecurrenceKind.WEEKDAYS -> "Dias úteis"
        RecurrenceKind.WEEKLY -> {
            val names = weekDays.sortedBy { it.value }.joinToString(" e ") { it.toPtBr() }
            if (names.isBlank()) "Semanal" else "Toda $names"
        }
        RecurrenceKind.MONTHLY -> "Todo dia ${dayOfMonth ?: "?"} do mês"
        RecurrenceKind.YEARLY -> {
            val month = monthOfYear?.toMonthPtBr() ?: "?"
            "Todo ${dayOfMonth ?: "?"} de $month"
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
        return !at.isBefore(now) || recurrence.isRecurring
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
    val endedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isEnded: Boolean get() = endedAt != null
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

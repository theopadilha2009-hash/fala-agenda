package com.theopadilha.falaagenda.domain.reminder

import com.theopadilha.falaagenda.domain.model.DraftSource
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object QuickRemind {
    fun draft(title: String, minutes: Long, now: ZonedDateTime): ParsedTaskDraft {
        val at = now.plusMinutes(minutes).truncatedTo(ChronoUnit.MINUTES)
        val trimmed = title.trim()
        val missing = if (trimmed.isBlank()) setOf(MissingDraftField.TITLE) else emptySet()
        return ParsedTaskDraft(
            title = trimmed,
            localDate = at.toLocalDate(),
            localTime = at.toLocalTime(),
            recurrence = RecurrenceRule(),
            confidence = if (missing.isEmpty()) 1.0 else 0.4,
            missingFields = missing,
            ambiguous = false,
            transcript = "",
            source = DraftSource.MANUAL,
        )
    }
}

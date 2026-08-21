package com.theopadilha.falaagenda.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class QuickConfirmTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant()

    private fun draft(
        date: LocalDate,
        time: LocalTime,
        ambiguous: Boolean = false,
        kind: RecurrenceKind = RecurrenceKind.NONE,
        title: String = "Remédio",
    ) = ParsedTaskDraft(
        title = title,
        localDate = date,
        localTime = time,
        recurrence = RecurrenceRule(kind),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = ambiguous,
        transcript = title,
    )

    @Test
    fun futuroCompletoPodeConfirmarRapido() {
        val d = draft(LocalDate.of(2026, 8, 20), LocalTime.of(18, 0))
        assertThat(d.canQuickConfirm(now, zone)).isTrue()
    }

    @Test
    fun passadoUnicoVaiParaTelaCheia() {
        val d = draft(LocalDate.of(2026, 8, 20), LocalTime.of(9, 0))
        assertThat(d.canQuickConfirm(now, zone)).isFalse()
    }

    @Test
    fun ambiguoVaiParaTelaCheia() {
        val d = draft(LocalDate.of(2026, 8, 21), LocalTime.of(8, 0), ambiguous = true)
        assertThat(d.canQuickConfirm(now, zone)).isFalse()
    }

    @Test
    fun incompletoNaoConfirmaRapido() {
        val d = ParsedTaskDraft(
            title = "Lembrar",
            localDate = LocalDate.of(2026, 8, 21),
            localTime = null,
            confidence = 0.4,
            missingFields = setOf(MissingDraftField.TIME),
            ambiguous = false,
            transcript = "lembrar amanha",
        )
        assertThat(d.canQuickConfirm(now, zone)).isFalse()
    }

    @Test
    fun recorrentePassadoAindaPodeRapido() {
        val d = draft(LocalDate.of(2026, 8, 20), LocalTime.of(9, 0), kind = RecurrenceKind.DAILY)
        assertThat(d.canQuickConfirm(now, zone)).isTrue()
    }
}

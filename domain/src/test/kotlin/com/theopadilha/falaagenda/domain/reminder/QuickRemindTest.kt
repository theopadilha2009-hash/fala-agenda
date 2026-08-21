package com.theopadilha.falaagenda.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.MissingDraftField
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class QuickRemindTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    @Test
    fun cincoMinutosNoMesmoDia() {
        val now = LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone)
        val draft = QuickRemind.draft("Tomar água", 5, now)
        assertThat(draft.localDate).isEqualTo(now.toLocalDate())
        assertThat(draft.localTime).isEqualTo(now.toLocalTime().plusMinutes(5))
        assertThat(draft.isComplete).isTrue()
        assertThat(draft.canQuickConfirm(now.toInstant(), zone)).isTrue()
    }

    @Test
    fun viraODia() {
        val now = LocalDateTime.of(2026, 8, 20, 23, 58).atZone(zone)
        val draft = QuickRemind.draft("Remédio", 5, now)
        assertThat(draft.localDate).isEqualTo(now.toLocalDate().plusDays(1))
        assertThat(draft.localTime!!.hour).isEqualTo(0)
        assertThat(draft.localTime!!.minute).isEqualTo(3)
    }

    @Test
    fun semTituloNaoCompleta() {
        val now = LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone)
        val draft = QuickRemind.draft("  ", 15, now)
        assertThat(draft.missingFields).contains(MissingDraftField.TITLE)
        assertThat(draft.isComplete).isFalse()
    }
}

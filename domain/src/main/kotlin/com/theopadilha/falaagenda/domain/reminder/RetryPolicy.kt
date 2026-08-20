package com.theopadilha.falaagenda.domain.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Remarca uma tarefa única não realizada no próximo horário de relógio ainda aberto:
 * hoje, se o horário original ainda não passou; senão amanhã no mesmo horário.
 * Não inventa outro relógio.
 */
object RetryPolicy {
    fun nextOpenDate(
        today: LocalDate,
        time: LocalTime,
        now: Instant,
        zone: ZoneId,
    ): LocalDate {
        val todayAt = today.atTime(time).atZone(zone).toInstant()
        return if (!todayAt.isBefore(now)) today else today.plusDays(1)
    }
}

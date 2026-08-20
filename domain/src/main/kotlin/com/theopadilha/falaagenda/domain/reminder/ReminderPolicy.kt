package com.theopadilha.falaagenda.domain.reminder

import com.theopadilha.falaagenda.domain.model.QuietHours
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Escada de lembretes:
 *  - passo 0: horário da ocorrência (toca mesmo em silêncio)
 *  - passo 1: +15 min após o anterior
 *  - passo 2: +30 min após o anterior
 *  - passo 3+: +60 min após o anterior
 *
 * Horário de silêncio pausa SOMENTE as repetições (passo >= 1).
 * A repetição retomada dispara às 08:00 (fim do silêncio) no fuso da série.
 */
object ReminderPolicy {
    const val STEP_FIRST = 0
    const val STEP_PLUS_15 = 1
    const val STEP_PLUS_30 = 2
    const val STEP_HOURLY = 3

    fun intervalAfterStep(stepJustFired: Int): java.time.Duration = when (stepJustFired) {
        STEP_FIRST -> java.time.Duration.ofMinutes(15)
        STEP_PLUS_15 -> java.time.Duration.ofMinutes(30)
        else -> java.time.Duration.ofMinutes(60)
    }

    fun nextStep(currentStep: Int): Int = when (currentStep) {
        STEP_FIRST -> STEP_PLUS_15
        STEP_PLUS_15 -> STEP_PLUS_30
        else -> STEP_HOURLY
    }

    data class Plan(
        val fireAt: Instant,
        val step: Int,
        val skippedQuietHours: Boolean,
    )

    fun firstReminder(occurrenceScheduledAt: Instant): Plan =
        Plan(fireAt = occurrenceScheduledAt, step = STEP_FIRST, skippedQuietHours = false)

    /**
     * Próxima repetição depois de um disparo (ou snooze).
     * [from] é o instante de referência (último disparo, snooze, ou now).
     */
    fun nextRepetition(
        from: Instant,
        nextStep: Int,
        zoneId: ZoneId,
        quietHours: QuietHours,
        interval: java.time.Duration,
    ): Plan {
        val raw = from.plus(interval)
        val adjusted = shiftOutOfQuietHours(raw, zoneId, quietHours)
        return Plan(
            fireAt = adjusted,
            step = nextStep,
            skippedQuietHours = adjusted != raw,
        )
    }

    fun snooze(
        from: Instant,
        minutes: Long = 30,
        zoneId: ZoneId,
        quietHours: QuietHours,
        respectQuietHours: Boolean = false,
    ): Plan {
        val raw = from.plus(minutes, ChronoUnit.MINUTES)
        // Snooze é ação explícita do usuário: toca no horário pedido.
        val fireAt = if (respectQuietHours) shiftOutOfQuietHours(raw, zoneId, quietHours) else raw
        return Plan(fireAt = fireAt, step = STEP_HOURLY, skippedQuietHours = fireAt != raw)
    }

    fun isInQuietHours(instant: Instant, zoneId: ZoneId, quietHours: QuietHours): Boolean {
        val time = instant.atZone(zoneId).toLocalTime()
        return isLocalTimeInQuietHours(time, quietHours)
    }

    fun isLocalTimeInQuietHours(time: LocalTime, quietHours: QuietHours): Boolean {
        val start = quietHours.start
        val end = quietHours.end
        return if (start <= end) {
            !time.isBefore(start) && time.isBefore(end)
        } else {
            // Ex.: 22:00 → 08:00
            !time.isBefore(start) || time.isBefore(end)
        }
    }

    /**
     * Se [instant] cair no silêncio, empurra para o fim do silêncio (ex.: 08:00).
     * Se já estiver fora, devolve o mesmo instante.
     */
    fun shiftOutOfQuietHours(instant: Instant, zoneId: ZoneId, quietHours: QuietHours): Instant {
        if (!isInQuietHours(instant, zoneId, quietHours)) return instant
        val zoned = instant.atZone(zoneId)
        val resume = resumeAt(zoned, quietHours)
        return resume.toInstant()
    }

    fun resumeAt(zoned: ZonedDateTime, quietHours: QuietHours): ZonedDateTime {
        val t = zoned.toLocalTime()
        val end = quietHours.end
        val start = quietHours.start
        return if (start <= end) {
            zoned.with(end).withSecond(0).withNano(0)
        } else {
            // 22h-8h: se estamos após 22h, resume no dia seguinte às 8h; se antes das 8h, hoje às 8h
            if (!t.isBefore(start)) {
                zoned.plusDays(1).with(end).withSecond(0).withNano(0)
            } else {
                zoned.with(end).withSecond(0).withNano(0)
            }
        }
    }
}

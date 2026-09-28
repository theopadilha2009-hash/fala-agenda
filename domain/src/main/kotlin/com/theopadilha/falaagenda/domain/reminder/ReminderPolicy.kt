package com.theopadilha.falaagenda.domain.reminder

import com.theopadilha.falaagenda.domain.model.QuietHours
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
 *
 * A escada termina no fim do dia local da ocorrência: passado esse dia não há nova
 * repetição, e a ocorrência volta a ser encerrada pela virada do dia ("não realizada").
 * A única travessia é o adiamento do silêncio, que empurra a última repetição do dia para
 * as 08:00 do dia seguinte — e é o último degrau.
 */
object ReminderPolicy {
    const val STEP_FIRST = 0
    const val STEP_PLUS_15 = 1
    const val STEP_PLUS_30 = 2
    const val STEP_HOURLY = 3

    /**
     * Teto absoluto de passos, rede de segurança contra qualquer caminho que escape da
     * regra do fim do dia (um dia inteiro de repetições de hora em hora fica bem abaixo).
     */
    const val MAX_STEP = 32

    fun intervalAfterStep(stepJustFired: Int): Duration = when (stepJustFired) {
        STEP_FIRST -> Duration.ofMinutes(15)
        STEP_PLUS_15 -> Duration.ofMinutes(30)
        else -> Duration.ofMinutes(60)
    }

    fun nextStep(currentStep: Int): Int = when (currentStep) {
        STEP_FIRST -> STEP_PLUS_15
        STEP_PLUS_15 -> STEP_PLUS_30
        else -> currentStep + 1
    }

    /** [fireAt] nulo significa escada encerrada: não há novo nextReminderAt. */
    data class Plan(
        val fireAt: Instant?,
        val step: Int,
        val skippedQuietHours: Boolean,
    )

    fun firstReminder(occurrenceScheduledAt: Instant): Plan =
        Plan(fireAt = occurrenceScheduledAt, step = STEP_FIRST, skippedQuietHours = false)

    /**
     * Próxima repetição depois de um disparo. [from] é o instante de referência (último
     * disparo) e [occurrenceDay] o dia local da ocorrência.
     *
     * Devolve um plano sem [Plan.fireAt] quando a escada termina: o passo seguinte cairia
     * fora do dia da ocorrência ou passaria do teto de passos. Sem novo nextReminderAt, a
     * virada do dia volta a marcar a ocorrência como não realizada.
     */
    fun nextRepetition(
        from: Instant,
        nextStep: Int,
        zoneId: ZoneId,
        quietHours: QuietHours,
        interval: Duration,
        occurrenceDay: LocalDate,
    ): Plan {
        if (nextStep > MAX_STEP) return ended(nextStep)
        val raw = from.plus(interval)
        // A repetição não atravessa o fim do dia da ocorrência. O silêncio pode empurrar o
        // disparo para as 08:00 do dia seguinte; essa travessia é o último degrau do dia.
        if (raw.atZone(zoneId).toLocalDate() != occurrenceDay) return ended(nextStep)
        val adjusted = shiftOutOfQuietHours(raw, zoneId, quietHours)
        return Plan(
            fireAt = adjusted,
            step = nextStep,
            skippedQuietHours = adjusted != raw,
        )
    }

    private fun ended(step: Int) = Plan(fireAt = null, step = step, skippedQuietHours = false)

    /**
     * Snooze é ação explícita da usuária e não é repetição: vale no horário pedido, mesmo
     * depois da meia-noite, e não é podado pelo fim do dia da ocorrência.
     */
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

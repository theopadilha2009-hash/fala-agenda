package com.theopadilha.falaagenda.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.QuietHours
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderPolicyTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val quiet = QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0))

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    @Test
    fun primeiroLembreteNoHorarioMesmoNoSilencio() {
        val scheduled = instant(2026, 8, 20, 23, 0)
        val plan = ReminderPolicy.firstReminder(scheduled)
        assertThat(plan.fireAt).isEqualTo(scheduled)
        assertThat(plan.step).isEqualTo(ReminderPolicy.STEP_FIRST)
        assertThat(ReminderPolicy.isInQuietHours(scheduled, zone, quiet)).isTrue()
    }

    @Test
    fun repeticaoQuinzeDepoisTrintaDepoisSessenta() {
        val t0 = instant(2026, 8, 20, 10, 0)
        val s1 = ReminderPolicy.nextRepetition(
            from = t0,
            nextStep = ReminderPolicy.STEP_PLUS_15,
            zoneId = zone,
            quietHours = quiet,
            interval = ReminderPolicy.intervalAfterStep(ReminderPolicy.STEP_FIRST),
        )
        assertThat(s1.fireAt).isEqualTo(instant(2026, 8, 20, 10, 15))
        val s2 = ReminderPolicy.nextRepetition(
            from = s1.fireAt,
            nextStep = ReminderPolicy.STEP_PLUS_30,
            zoneId = zone,
            quietHours = quiet,
            interval = ReminderPolicy.intervalAfterStep(ReminderPolicy.STEP_PLUS_15),
        )
        assertThat(s2.fireAt).isEqualTo(instant(2026, 8, 20, 10, 45))
        val s3 = ReminderPolicy.nextRepetition(
            from = s2.fireAt,
            nextStep = ReminderPolicy.STEP_HOURLY,
            zoneId = zone,
            quietHours = quiet,
            interval = ReminderPolicy.intervalAfterStep(ReminderPolicy.STEP_PLUS_30),
        )
        assertThat(s3.fireAt).isEqualTo(instant(2026, 8, 20, 11, 45))
    }

    @Test
    fun repeticaoNoSilencioRetomaAsOito() {
        val last = instant(2026, 8, 20, 21, 50)
        val plan = ReminderPolicy.nextRepetition(
            from = last,
            nextStep = ReminderPolicy.STEP_PLUS_15,
            zoneId = zone,
            quietHours = quiet,
            interval = java.time.Duration.ofMinutes(15),
        )
        assertThat(plan.fireAt).isEqualTo(instant(2026, 8, 21, 8, 0))
        assertThat(plan.skippedQuietHours).isTrue()
    }

    @Test
    fun snoozeTrintaMinutosNaoRespeitaSilencioPorPadrao() {
        val from = instant(2026, 8, 20, 21, 50)
        val plan = ReminderPolicy.snooze(from, 30, zone, quiet, respectQuietHours = false)
        assertThat(plan.fireAt).isEqualTo(instant(2026, 8, 20, 22, 20))
    }

    @Test
    fun quietHoursDetectaFaixaNoturna() {
        assertThat(ReminderPolicy.isInQuietHours(instant(2026, 8, 20, 22, 0), zone, quiet)).isTrue()
        assertThat(ReminderPolicy.isInQuietHours(instant(2026, 8, 21, 7, 59), zone, quiet)).isTrue()
        assertThat(ReminderPolicy.isInQuietHours(instant(2026, 8, 21, 8, 0), zone, quiet)).isFalse()
        assertThat(ReminderPolicy.isInQuietHours(instant(2026, 8, 20, 21, 59), zone, quiet)).isFalse()
    }
}

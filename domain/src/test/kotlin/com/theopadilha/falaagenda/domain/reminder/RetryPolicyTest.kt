package com.theopadilha.falaagenda.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class RetryPolicyTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val today = LocalDate.of(2026, 8, 20)

    @Test
    fun horarioAindaAbertoFicaHoje() {
        val now = LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant()
        val date = RetryPolicy.nextOpenDate(today, LocalTime.of(14, 0), now, zone)
        assertThat(date).isEqualTo(today)
    }

    @Test
    fun horarioJaPassouVaiParaAmanha() {
        val now = LocalDateTime.of(2026, 8, 20, 16, 0).atZone(zone).toInstant()
        val date = RetryPolicy.nextOpenDate(today, LocalTime.of(14, 0), now, zone)
        assertThat(date).isEqualTo(today.plusDays(1))
    }

    @Test
    fun horarioExatoAgoraFicaHoje() {
        val now = LocalDateTime.of(2026, 8, 20, 14, 0).atZone(zone).toInstant()
        val date = RetryPolicy.nextOpenDate(today, LocalTime.of(14, 0), now, zone)
        assertThat(date).isEqualTo(today)
    }
}

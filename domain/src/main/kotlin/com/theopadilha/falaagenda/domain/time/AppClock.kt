package com.theopadilha.falaagenda.domain.time

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Relógio e fuso injetáveis. Produção usa o sistema; testes usam instante fixo.
 */
interface AppClock {
    fun instant(): Instant
    fun zoneId(): ZoneId

    fun now(): ZonedDateTime = instant().atZone(zoneId())
    fun today(): LocalDate = now().toLocalDate()
    fun localTime(): LocalTime = now().toLocalTime()
}

class SystemAppClock(
    private val clock: Clock = Clock.systemDefaultZone(),
) : AppClock {
    override fun instant(): Instant = clock.instant()
    override fun zoneId(): ZoneId = clock.zone
}

class FixedAppClock(
    private val fixedInstant: Instant,
    private val zone: ZoneId,
) : AppClock {
    override fun instant(): Instant = fixedInstant
    override fun zoneId(): ZoneId = zone

    fun at(dateTime: LocalDateTime): FixedAppClock =
        FixedAppClock(dateTime.atZone(zone).toInstant(), zone)
}

fun AppClock.withZone(zone: ZoneId): AppClock = object : AppClock {
    override fun instant(): Instant = this@withZone.instant()
    override fun zoneId(): ZoneId = zone
}

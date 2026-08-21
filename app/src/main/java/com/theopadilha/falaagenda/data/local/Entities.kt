package com.theopadilha.falaagenda.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Entity(tableName = "task_series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val title: String,
    val zoneId: String,
    val localTime: String,
    val startLocalDate: String,
    val recurrenceKind: String,
    val weekDays: String,
    val dayOfMonth: Int?,
    val monthOfYear: Int?,
    val amountCents: Long? = null,
    val observation: String = "",
    val endedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "task_occurrences",
    indices = [Index("seriesId"), Index("status"), Index("localDate")],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val seriesId: String,
    val localDate: String,
    val scheduledAtEpochMs: Long,
    val status: String,
    val completedAtEpochMs: Long?,
    val missedAtEpochMs: Long?,
    val reminderStep: Int,
    val nextReminderAtEpochMs: Long?,
    val lastReminderAtEpochMs: Long?,
    val snoozedUntilEpochMs: Long?,
    val inexactAlarm: Boolean,
)

fun SeriesEntity.toDomain(): TaskSeries = TaskSeries(
    id = id,
    title = title,
    zoneId = ZoneId.of(zoneId),
    localTime = LocalTime.parse(localTime),
    startLocalDate = LocalDate.parse(startLocalDate),
    recurrence = RecurrenceRule(
        kind = RecurrenceKind.valueOf(recurrenceKind),
        weekDays = weekDays.split(",").filter { it.isNotBlank() }.map { DayOfWeek.valueOf(it) }.toSet(),
        dayOfMonth = dayOfMonth,
        monthOfYear = monthOfYear,
    ),
    amountCents = amountCents,
    observation = observation,
    endedAt = endedAtEpochMs?.let { Instant.ofEpochMilli(it) },
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
)

fun TaskSeries.toEntity(): SeriesEntity = SeriesEntity(
    id = id,
    title = title,
    zoneId = zoneId.id,
    localTime = localTime.toString(),
    startLocalDate = startLocalDate.toString(),
    recurrenceKind = recurrence.kind.name,
    weekDays = recurrence.weekDays.joinToString(",") { it.name },
    dayOfMonth = recurrence.dayOfMonth,
    monthOfYear = recurrence.monthOfYear,
    amountCents = amountCents,
    observation = observation,
    endedAtEpochMs = endedAt?.toEpochMilli(),
    createdAtEpochMs = createdAt.toEpochMilli(),
    updatedAtEpochMs = updatedAt.toEpochMilli(),
)

fun OccurrenceEntity.toDomain(): TaskOccurrence = TaskOccurrence(
    id = id,
    seriesId = seriesId,
    localDate = LocalDate.parse(localDate),
    scheduledAt = Instant.ofEpochMilli(scheduledAtEpochMs),
    status = OccurrenceStatus.valueOf(status),
    completedAt = completedAtEpochMs?.let { Instant.ofEpochMilli(it) },
    missedAt = missedAtEpochMs?.let { Instant.ofEpochMilli(it) },
    reminderStep = reminderStep,
    nextReminderAt = nextReminderAtEpochMs?.let { Instant.ofEpochMilli(it) },
    lastReminderAt = lastReminderAtEpochMs?.let { Instant.ofEpochMilli(it) },
    snoozedUntil = snoozedUntilEpochMs?.let { Instant.ofEpochMilli(it) },
    inexactAlarm = inexactAlarm,
)

fun TaskOccurrence.toEntity(): OccurrenceEntity = OccurrenceEntity(
    id = id,
    seriesId = seriesId,
    localDate = localDate.toString(),
    scheduledAtEpochMs = scheduledAt.toEpochMilli(),
    status = status.name,
    completedAtEpochMs = completedAt?.toEpochMilli(),
    missedAtEpochMs = missedAt?.toEpochMilli(),
    reminderStep = reminderStep,
    nextReminderAtEpochMs = nextReminderAt?.toEpochMilli(),
    lastReminderAtEpochMs = lastReminderAt?.toEpochMilli(),
    snoozedUntilEpochMs = snoozedUntil?.toEpochMilli(),
    inexactAlarm = inexactAlarm,
)

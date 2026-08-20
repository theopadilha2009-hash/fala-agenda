package com.theopadilha.falaagenda.reminders

import com.theopadilha.falaagenda.data.repo.SchedulerOutcome
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries

interface AlarmScheduler {
    suspend fun quietHours(): QuietHours
    fun canScheduleExact(): Boolean
    fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean): SchedulerOutcome
    fun cancel(occurrenceId: String)
}

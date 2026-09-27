package com.theopadilha.falaagenda.data.repo

import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.local.toDomain
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.recurrence.OccurrenceLifecycle
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import com.theopadilha.falaagenda.domain.reminder.ReminderPolicy
import com.theopadilha.falaagenda.domain.reminder.RetryPolicy
import com.theopadilha.falaagenda.domain.time.AppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AgendaItem(
    val occurrence: TaskOccurrence,
    val series: TaskSeries,
)

data class AgendaSections(
    val today: List<AgendaItem>,
    val upcoming: List<AgendaItem>,
    val completed: List<AgendaItem>,
    val missed: List<AgendaItem>,
) {
    fun find(occurrenceId: String): AgendaItem? =
        (today + upcoming + completed + missed).firstOrNull { it.occurrence.id == occurrenceId }
}

class TaskRepository(
    private val seriesDao: SeriesDao,
    private val occurrenceDao: OccurrenceDao,
    private val clock: AppClock,
    private val scheduler: AlarmScheduler,
) {
    /**
     * Um escritor por vez. Todo start de processo dispara `rescheduleAll` num escopo de
     * fundo e o receiver do alarme pode disparar `onAlarmFired` em paralelo: sem isto a
     * varredura lia o banco antes e gravava depois do disparo (ou de um "Excluir" da
     * usuária) e desfazia o que o outro caminho tinha acabado de decidir.
     *
     * Só as mutações passam por aqui — a agenda lida pela tela não espera por elas.
     */
    private val writer = Mutex()

    fun observeAgenda(): Flow<AgendaSections> = combine(
        seriesDao.observeAll(),
        occurrenceDao.observeAll(),
    ) { seriesRows, occurrenceRows ->
        sectionsOf(seriesRows, occurrenceRows)
    }

    suspend fun snapshotAgenda(): AgendaSections =
        sectionsOf(seriesDao.getAll(), occurrenceDao.getAll())

    private fun sectionsOf(
        seriesRows: List<SeriesEntity>,
        occurrenceRows: List<OccurrenceEntity>,
    ): AgendaSections {
        val series = seriesRows.associate { it.id to it.toDomain() }
        val items = occurrenceRows.mapNotNull { row ->
            val s = series[row.seriesId] ?: return@mapNotNull null
            AgendaItem(row.toDomain(), s)
        }
        val today = clock.today()
        val pending = items.filter { it.occurrence.status == OccurrenceStatus.PENDING }
        // A pendente que atravessou a meia-noite (aviso adiado pela noite, ainda tocando)
        // continua acionável: ela entra em "Hoje" e vem antes das de hoje, porque é a mais
        // urgente. Fora daqui ela não caía em nenhuma das quatro seções — invisível no app,
        // e o toque na notificação respondia "Esta tarefa não está mais na agenda".
        val (atrasadas, deHoje) = pending.partition { it.occurrence.localDate.isBefore(today) }
        return AgendaSections(
            today = atrasadas.sortedBy { it.occurrence.scheduledAt } +
                deHoje.filter { it.occurrence.localDate == today }
                    .sortedBy { it.occurrence.scheduledAt },
            upcoming = pending.filter { it.occurrence.localDate.isAfter(today) }
                .sortedBy { it.occurrence.scheduledAt },
            completed = items.filter { it.occurrence.status == OccurrenceStatus.COMPLETED }
                .sortedByDescending { it.occurrence.completedAt },
            missed = items.filter { it.occurrence.status == OccurrenceStatus.MISSED }
                .sortedByDescending { it.occurrence.missedAt },
        )
    }

    suspend fun saveDraft(draft: ParsedTaskDraft): SaveResult = writer.withLock {
        require(draft.isComplete) { "Confirme título, data e horário antes de salvar." }
        val now = clock.instant()
        val zone = clock.zoneId()
        val series = TaskSeries(
            id = UUID.randomUUID().toString(),
            title = draft.title.trim(),
            zoneId = zone,
            localTime = draft.localTime!!,
            startLocalDate = draft.localDate!!,
            recurrence = draft.recurrence,
            amountCents = draft.amountCents,
            observation = draft.observation.trim(),
            createdAt = now,
            updatedAt = now,
        )
        val firstDate = RecurrenceEngine.firstOnOrAfter(
            series.recurrence,
            series.startLocalDate,
            series.startLocalDate,
        ) ?: series.startLocalDate
        var occurrence = OccurrenceLifecycle.materialize(series, firstDate, now)
        val scheduledAt = occurrence.scheduledAt
        if (scheduledAt.isBefore(now) && series.recurrence.kind == RecurrenceKind.NONE) {
            occurrence = occurrence.copy(
                status = OccurrenceStatus.MISSED,
                missedAt = now,
                nextReminderAt = null,
            )
        }
        val scheduled = if (occurrence.status == OccurrenceStatus.PENDING) {
            scheduler.schedule(occurrence, series, first = true)
        } else {
            SchedulerOutcome(inexact = false, scheduled = false)
        }
        val stored = occurrence.copy(inexactAlarm = scheduled.inexact)
        // Série e primeira ocorrência na mesma transação: morrer entre as duas deixava a
        // tarefa recém-cadastrada existindo só pela metade.
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = listOf(stored.toEntity()),
            deletes = emptyList(),
            series = series.toEntity(),
            deleteSeriesRow = false,
        )
        SaveResult(series = series, occurrence = stored, usedInexactAlarm = scheduled.inexact)
    }

    suspend fun complete(occurrenceId: String) = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return@withLock
        val occurrence = row.toDomain()
        if (occurrence.status != OccurrenceStatus.PENDING &&
            occurrence.status != OccurrenceStatus.MISSED
        ) {
            return@withLock
        }
        scheduler.cancel(occurrence.id)
        val done = occurrence.copy(
            status = OccurrenceStatus.COMPLETED,
            completedAt = now,
            nextReminderAt = null,
        )
        occurrenceDao.upsert(done.toEntity())
        spawnNextIfNeeded(series, done.localDate, now)
    }

    suspend fun uncomplete(item: AgendaItem) = writer.withLock {
        val now = clock.instant()
        val series = item.series.copy(endedAt = null, updatedAt = now)
        val serieRow = series.toEntity()
        when (item.occurrence.status) {
            OccurrenceStatus.MISSED -> {
                occurrenceDao.applyBatch(
                    seriesDao = seriesDao,
                    upserts = listOf(item.occurrence.toEntity()),
                    deletes = emptyList(),
                    series = serieRow,
                    deleteSeriesRow = false,
                )
            }
            OccurrenceStatus.PENDING -> {
                var occ = item.occurrence.copy(completedAt = null)
                val next = occ.nextReminderAt
                val stillArmed = next != null && !next.isBefore(now)
                val stillOnClock = !occ.scheduledAt.isBefore(now)
                if (!stillArmed && !stillOnClock) {
                    val quiet = scheduler.quietHours()
                    val plan = ReminderPolicy.snooze(now, 1, series.zoneId, quiet, respectQuietHours = false)
                    occ = occ.copy(
                        snoozedUntil = plan.fireAt,
                        nextReminderAt = plan.fireAt,
                        reminderStep = plan.step,
                    )
                }
                val scheduled = scheduler.schedule(
                    occ,
                    series,
                    first = occ.reminderStep == 0 && occ.lastReminderAt == null && occ.snoozedUntil == null,
                )
                occurrenceDao.applyBatch(
                    seriesDao = seriesDao,
                    upserts = listOf(occ.copy(inexactAlarm = scheduled.inexact).toEntity()),
                    deletes = emptyList(),
                    series = serieRow,
                    deleteSeriesRow = false,
                )
            }
            else -> seriesDao.upsert(serieRow)
        }
    }

    suspend fun deleteOccurrence(occurrenceId: String) = writer.withLock {
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock
        scheduler.cancel(occurrenceId)
        val series = seriesDao.get(row.seriesId)?.toDomain()
        val leftover = occurrenceDao.forSeries(row.seriesId).filterNot { it.id == occurrenceId }
        // "Excluir" é só aquela data. Numa série recorrente apagar a série aqui era o
        // mesmo que "Encerrar série", que a tela oferece como ação separada.
        if (leftover.isEmpty() && series?.recurrence?.isRecurring != true) {
            occurrenceDao.applyBatch(
                seriesDao = seriesDao,
                upserts = emptyList(),
                deletes = listOf(occurrenceId),
                series = series?.toEntity(),
                deleteSeriesRow = series != null,
            )
            return@withLock
        }
        // Sem o tombstone a rotina de avanço rematerializa a data apagada no próximo start.
        if (series == null || !series.recurrence.isRecurring) {
            occurrenceDao.applyBatch(
                seriesDao = seriesDao,
                upserts = emptyList(),
                deletes = listOf(occurrenceId),
                series = null,
                deleteSeriesRow = false,
            )
            return@withLock
        }
        val now = clock.instant()
        val skipped = OccurrenceLifecycle.skipDate(
            series.skippedDates,
            row.toDomain().localDate,
            OccurrenceLifecycle.todayIn(series.zoneId, now),
        )
        // Apagar a data e gravar o tombstone na mesma transação: o processo morto no meio
        // deixava a data apagada sem tombstone, e a varredura do próximo start a trazia de
        // volta como se nada tivesse acontecido.
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = emptyList(),
            deletes = listOf(occurrenceId),
            series = series.copy(skippedDates = skipped, updatedAt = now).toEntity(),
            deleteSeriesRow = false,
        )
    }

    suspend fun restore(item: AgendaItem) = writer.withLock {
        val now = clock.instant()
        // Desfazer o "Excluir" tem que tirar o tombstone junto: deixá-lo marcado
        // bloquearia a data de voltar em qualquer materialização futura.
        val series = item.series.copy(
            endedAt = null,
            skippedDates = OccurrenceLifecycle.unskipDate(
                item.series.skippedDates,
                item.occurrence.localDate,
            ),
            updatedAt = now,
        )
        val fresh = OccurrenceLifecycle.materialize(series, item.occurrence.localDate, now)
        if (fresh.scheduledAt.isBefore(now) && !series.recurrence.isRecurring) {
            occurrenceDao.applyBatch(
                seriesDao = seriesDao,
                upserts = listOf(
                    fresh.copy(
                        status = OccurrenceStatus.MISSED,
                        missedAt = now,
                        nextReminderAt = null,
                    ).toEntity(),
                ),
                deletes = emptyList(),
                series = series.toEntity(),
                deleteSeriesRow = false,
            )
            return@withLock
        }
        val scheduled = scheduler.schedule(fresh, series, first = true)
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = listOf(fresh.copy(inexactAlarm = scheduled.inexact).toEntity()),
            deletes = emptyList(),
            series = series.toEntity(),
            deleteSeriesRow = false,
        )
    }

    suspend fun endSeries(seriesId: String) = writer.withLock {
        val now = clock.instant()
        val series = seriesDao.get(seriesId)?.toDomain() ?: return@withLock
        val ended = series.copy(endedAt = now, updatedAt = now)
        val pending = occurrenceDao.forSeries(seriesId)
            .map { it.toDomain() }
            .filter { it.status == OccurrenceStatus.PENDING }
        pending.forEach { scheduler.cancel(it.id) }
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = pending.map {
                it.copy(status = OccurrenceStatus.CANCELLED, nextReminderAt = null).toEntity()
            },
            deletes = emptyList(),
            series = ended.toEntity(),
            deleteSeriesRow = false,
        )
    }

    suspend fun editOccurrence(
        occurrenceId: String,
        title: String,
        date: java.time.LocalDate,
        time: java.time.LocalTime,
        recurrence: com.theopadilha.falaagenda.domain.model.RecurrenceRule,
        amountCents: Long? = null,
        observation: String = "",
    ) = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return@withLock
        val original = row.toDomain()
        val sameWhen = original.localDate == date && series.localTime == time
        val finished = original.status == OccurrenceStatus.COMPLETED ||
            original.status == OccurrenceStatus.MISSED
        if (finished && sameWhen && recurrence == series.recurrence) {
            seriesDao.upsert(
                series.copy(
                    title = title.trim(),
                    amountCents = amountCents,
                    observation = observation.trim(),
                    updatedAt = now,
                ).toEntity(),
            )
            return@withLock
        }
        val pending = occurrenceDao.forSeries(series.id)
            .map { it.toDomain() }
            .filter { it.status == OccurrenceStatus.PENDING }
        pending.forEach { scheduler.cancel(it.id) }
        val updatedSeries = series.copy(
            title = title.trim(),
            localTime = time,
            startLocalDate = date,
            recurrence = recurrence,
            amountCents = amountCents,
            observation = observation.trim(),
            updatedAt = now,
            // Citar de volta para uma data excluída desfaz a exclusão: com o tombstone
            // marcado junto da ocorrência viva, a data ficaria bloqueada em toda
            // materialização futura.
            skippedDates = OccurrenceLifecycle.unskipDate(series.skippedDates, date),
        )
        val refreshed = OccurrenceLifecycle.materialize(updatedSeries, date, now)
        val serieRow = updatedSeries.toEntity()
        val substituidas = pending.map { it.id }
        if (refreshed.scheduledAt.isBefore(now) && !updatedSeries.recurrence.isRecurring) {
            occurrenceDao.applyBatch(
                seriesDao = seriesDao,
                upserts = listOf(
                    refreshed.copy(
                        status = OccurrenceStatus.MISSED,
                        missedAt = now,
                        nextReminderAt = null,
                    ).toEntity(),
                ),
                deletes = substituidas,
                series = serieRow,
                deleteSeriesRow = false,
            )
            return@withLock
        }
        val scheduled = scheduler.schedule(refreshed, updatedSeries, first = true)
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = listOf(refreshed.copy(inexactAlarm = scheduled.inexact).toEntity()),
            deletes = substituidas,
            series = serieRow,
            deleteSeriesRow = false,
        )
        // O preview é sobre o que vem depois: ancorado na data editada, editar uma data
        // passada criava três datas vencidas, o próximo avanço marcava todas como não
        // realizadas e a agenda ficava sem as futuras até o app reabrir.
        spawnUpcomingPreview(updatedSeries, OccurrenceLifecycle.todayIn(updatedSeries.zoneId, now))
    }

    suspend fun retryMissed(occurrenceId: String): RetryResult? = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock null
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return@withLock null
        val occurrence = row.toDomain()
        if (occurrence.status != OccurrenceStatus.MISSED) return@withLock null
        if (series.recurrence.isRecurring) return@withLock null
        val date = RetryPolicy.nextOpenDate(clock.today(), series.localTime, now, series.zoneId)
        scheduler.cancel(occurrence.id)
        val updatedSeries = series.copy(
            startLocalDate = date,
            endedAt = null,
            updatedAt = now,
        )
        val fresh = OccurrenceLifecycle.materialize(updatedSeries, date, now)
        val scheduled = scheduler.schedule(fresh, updatedSeries, first = true)
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = listOf(fresh.copy(inexactAlarm = scheduled.inexact).toEntity()),
            deletes = listOf(occurrence.id),
            series = updatedSeries.toEntity(),
            deleteSeriesRow = false,
        )
        RetryResult(date = date, time = series.localTime)
    }

    suspend fun snooze(occurrenceId: String, minutes: Long = 30) = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return@withLock
        val occurrence = row.toDomain()
        if (occurrence.status != OccurrenceStatus.PENDING) return@withLock
        val quiet = scheduler.quietHours()
        val plan = ReminderPolicy.snooze(now, minutes, series.zoneId, quiet, respectQuietHours = false)
        val updated = occurrence.copy(
            snoozedUntil = plan.fireAt,
            nextReminderAt = plan.fireAt,
            reminderStep = plan.step,
        )
        val scheduled = scheduler.schedule(updated, series, first = false)
        occurrenceDao.upsert(updated.copy(inexactAlarm = scheduled.inexact).toEntity())
    }

    suspend fun onAlarmFired(occurrenceId: String): AlarmFireResult = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock AlarmFireResult(false)
        val series = seriesDao.get(row.seriesId)?.toDomain() ?: return@withLock AlarmFireResult(false)
        val occurrence = row.toDomain()
        // O disparo é resolvido antes da varredura de ciclo de vida: a varredura marcaria
        // como não realizada a ocorrência de ontem cujo aviso está tocando neste instante
        // (adiado pela noite, ele só chega no fim do silêncio) e o lembrete morreria
        // exatamente quando devia soar.
        val result = if (occurrence.status == OccurrenceStatus.PENDING && !series.isEnded) {
            fire(occurrence, series, now)
        } else {
            scheduler.cancel(occurrenceId)
            AlarmFireResult(false)
        }
        applyLifecycle(series, now)
        result
    }

    private suspend fun fire(
        occurrence: TaskOccurrence,
        series: TaskSeries,
        now: Instant,
    ): AlarmFireResult {
        val quiet = scheduler.quietHours()
        if (occurrence.reminderStep > 0 && ReminderPolicy.isInQuietHours(now, series.zoneId, quiet)) {
            val resume = ReminderPolicy.shiftOutOfQuietHours(now, series.zoneId, quiet)
            val deferred = occurrence.copy(nextReminderAt = resume)
            val scheduled = scheduler.schedule(deferred, series, first = false)
            occurrenceDao.upsert(deferred.copy(inexactAlarm = scheduled.inexact).toEntity())
            return AlarmFireResult(false)
        }
        val nextStep = ReminderPolicy.nextStep(occurrence.reminderStep)
        val interval = ReminderPolicy.intervalAfterStep(occurrence.reminderStep)
        val plan = ReminderPolicy.nextRepetition(
            from = now,
            nextStep = nextStep,
            zoneId = series.zoneId,
            quietHours = quiet,
            interval = interval,
            occurrenceDay = occurrence.localDate,
        )
        val updated = occurrence.copy(
            reminderStep = plan.step,
            lastReminderAt = now,
            nextReminderAt = plan.fireAt,
        )
        val scheduled = scheduler.schedule(updated, series, first = false)
        occurrenceDao.upsert(updated.copy(inexactAlarm = scheduled.inexact).toEntity())
        return AlarmFireResult(notify = true, title = series.title, seriesId = series.id)
    }

    /**
     * Rede de segurança do receiver: se o tratamento de um alarme não terminou a tempo
     * (processo morto, tempo esgotado), o mesmo disparo volta daqui a pouco em vez de a
     * escada de repetições morrer em silêncio até o app ser aberto de novo.
     */
    fun scheduleRecovery(occurrenceId: String) {
        scheduler.scheduleRecovery(occurrenceId, clock.instant().plusSeconds(RECOVERY_DELAY_SECONDS))
    }

    suspend fun rescheduleAll() = writer.withLock {
        val now = clock.instant()
        val seriesList = seriesDao.getAll().map { it.toDomain() }
        seriesList.forEach { series ->
            applyLifecycle(series, now)
        }
        occurrenceDao.getAll().map { it.toDomain() }
            .filter { it.status == OccurrenceStatus.PENDING }
            .forEach { occ ->
                val series = seriesDao.get(occ.seriesId)?.toDomain() ?: return@forEach
                val scheduled = scheduler.schedule(
                    occ,
                    series,
                    first = occ.reminderStep == 0 && occ.lastReminderAt == null,
                )
                occurrenceDao.upsert(occ.copy(inexactAlarm = scheduled.inexact).toEntity())
            }
    }

    private suspend fun applyLifecycle(series: TaskSeries, now: Instant) {
        val today = OccurrenceLifecycle.todayIn(series.zoneId, now)
        val existing = occurrenceDao.forSeries(series.id).map { it.toDomain() }
        val change = OccurrenceLifecycle.advance(series, existing, now, today)
        // A varredura decide "não realizada" olhando só o relógio. Um lembrete marcado para
        // um instante que já passou mas nunca foi entregue é entrega pendente, não ocorrência
        // vencida: o disparo atrasado pelo Doze ainda vai tocar, e arquivar aqui matava o
        // único aviso do dia (com o "Adiar" da notificação virando no-op).
        val pendentes = existing.filter { entregaPendente(it, now) }.associateBy { it.id }
        change.cancelAlarmsOf
            .filterNot { pendentes.containsKey(it) }
            .forEach { scheduler.cancel(it) }
        change.upserts
            .filterNot { it.status == OccurrenceStatus.MISSED && pendentes.containsKey(it.id) }
            .forEach { occurrenceDao.upsert(it.toEntity()) }
        spawnUpcomingPreview(series, today)
    }

    /**
     * Alarme marcado para um instante que já passou e nunca foi entregue (`lastReminderAt`
     * antes dele) ainda é entrega pendente. Vale até [JANELA_ENTREGA_PENDENTE] depois do
     * horário marcado — dentro dela o `rescheduleAll` de todo start rearma o aviso; passada
     * ela, o `advance` volta a decidir e a ocorrência vira não realizada, que é o terminador
     * da entrega pendente (nada fica pendurado para sempre).
     */
    private fun entregaPendente(occurrence: TaskOccurrence, now: Instant): Boolean {
        val marcado = occurrence.nextReminderAt ?: return false
        val ultimoAviso = occurrence.lastReminderAt ?: return false
        return !marcado.isAfter(now) &&
            ultimoAviso.isBefore(marcado) &&
            now.isBefore(marcado.plus(JANELA_ENTREGA_PENDENTE))
    }

    private suspend fun spawnNextIfNeeded(series: TaskSeries, completedDate: java.time.LocalDate, now: Instant) {
        if (series.isEnded || !series.recurrence.isRecurring) return
        val nextDate = RecurrenceEngine.nextAfter(series.recurrence, series.startLocalDate, completedDate) ?: return
        if (series.isSkipped(nextDate)) return
        val existing = occurrenceDao.get(com.theopadilha.falaagenda.domain.model.OccurrenceIds.of(series.id, nextDate))
        if (existing != null) return
        val next = OccurrenceLifecycle.materialize(series, nextDate, now)
        val scheduled = scheduler.schedule(next, series, first = true)
        occurrenceDao.upsert(next.copy(inexactAlarm = scheduled.inexact).toEntity())
    }

    private suspend fun spawnUpcomingPreview(series: TaskSeries, today: java.time.LocalDate) {
        if (series.isEnded || !series.recurrence.isRecurring) return
        RecurrenceEngine.upcoming(series.recurrence, series.startLocalDate, today, 3).forEach { date ->
            // Sem esta checagem o preview desfaz o tombstone: `upcoming` começa em hoje,
            // então a data que o usuário acabou de excluir era a primeira da lista.
            if (series.isSkipped(date)) return@forEach
            val id = com.theopadilha.falaagenda.domain.model.OccurrenceIds.of(series.id, date)
            if (occurrenceDao.get(id) == null) {
                val occ = OccurrenceLifecycle.materialize(series, date, clock.instant())
                val scheduled = scheduler.schedule(occ, series, first = true)
                occurrenceDao.upsert(occ.copy(inexactAlarm = scheduled.inexact).toEntity())
            }
        }
    }

    companion object {
        /** Curto de propósito: recuperação não pode virar trabalho sem limite. */
        const val RECOVERY_DELAY_SECONDS = 60L

        /**
         * Quanto tempo um lembrete marcado e não entregue ainda conta como "vai tocar".
         * Cobre o atraso real de entrega (Doze, alarme inexato, aparelho que ficou
         * desligado) sem ressuscitar um aviso do dia anterior.
         */
        val JANELA_ENTREGA_PENDENTE: Duration = Duration.ofHours(6)
    }
}

data class SaveResult(
    val series: TaskSeries,
    val occurrence: TaskOccurrence,
    val usedInexactAlarm: Boolean,
)

data class SchedulerOutcome(
    val inexact: Boolean,
    val scheduled: Boolean,
)

data class AlarmFireResult(
    val notify: Boolean,
    val title: String = "",
    val seriesId: String = "",
)

data class RetryResult(
    val date: java.time.LocalDate,
    val time: java.time.LocalTime,
)

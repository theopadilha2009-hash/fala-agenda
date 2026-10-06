package com.theopadilha.falaagenda.data.repo

import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.local.toDomain
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.recurrence.OccurrenceLifecycle
import com.theopadilha.falaagenda.domain.recurrence.RecurrenceEngine
import com.theopadilha.falaagenda.domain.reminder.DraftSchedule
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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

    /**
     * O retrato que o widget mostra: só o que ainda está de pé.
     *
     * Igual a [snapshotAgenda] no que o widget lê — `today` e `upcoming` saem exclusivamente das
     * pendentes, em [sectionsOf] como aqui —, mas sem puxar o histórico: as concluídas e as não
     * realizadas ficam de fora da leitura, não só do filtro. Ele repinta no boot, na troca de
     * hora e em toda virada do dia, e o que cresce sem poda é justamente o que ele não mostra.
     */
    suspend fun snapshotPendentes(): AgendaSections =
        sectionsOf(seriesDao.getAll(), occurrenceDao.byStatus(OccurrenceStatus.PENDING.name))

    /**
     * A série entra no cálculo de disparo com o fuso do relógio AGORA, nunca com o que ficou
     * gravado no dia do cadastro. Aqui é uma pessoa, um celular, um app: o horário que ela lê
     * na tela é o horário local dela hoje, e o aviso tem que tocar nele. Com o fuso velho,
     * trocar o fuso do celular deixava todo aviso já criado tocando 08:00 do lugar antigo —
     * deslocado, calado e para sempre. A próxima escrita da série grava o fuso atual e cura a
     * linha; `agenda` e varredura leem pelo mesmo caminho, então o "hoje" das duas é o mesmo.
     */
    private fun SeriesEntity.toTaskSeries(): TaskSeries = toDomain().copy(zoneId = clock.zoneId())

    private fun sectionsOf(
        seriesRows: List<SeriesEntity>,
        occurrenceRows: List<OccurrenceEntity>,
    ): AgendaSections {
        val series = seriesRows.associate { it.id to it.toTaskSeries() }
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
        // A mesma conta que a tela usa para prometer (ver `AgendaFormat.promiseOfChoice`): a
        // primeira ocorrência de uma regra que repete nunca nasce num instante vencido — um
        // alarme no passado dispara na hora. Aqui a escolha é piso e a data vencida não chega a
        // virar ocorrência; a edição e o "Desfazer" têm outro contrato e passam por
        // `occurrencesForChoice`, que arquiva a data vencida em vez de descartá-la.
        val firstDate = DraftSchedule.firstOccurrence(
            rule = series.recurrence,
            chosenDate = series.startLocalDate,
            chosenTime = series.localTime,
            zoneId = zone,
            now = now,
        ).date
        var occurrence = OccurrenceLifecycle.materialize(series, firstDate, now)
        if (DraftSchedule.bornWithoutReminder(occurrence.scheduledAt, series.recurrence, now)) {
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

    suspend fun complete(occurrenceId: String): ActionOutcome = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock ActionOutcome.GONE
        val series = seriesDao.get(row.seriesId)?.toTaskSeries() ?: return@withLock ActionOutcome.GONE
        val occurrence = row.toDomain()
        if (occurrence.status != OccurrenceStatus.PENDING &&
            occurrence.status != OccurrenceStatus.MISSED
        ) {
            // Já estava concluída (ou cancelada): não há o que gravar e nada se perdeu. Quem
            // chamou tem que calar — anunciar falha aqui seria trocar a mentira pelo alarme
            // falso.
            return@withLock ActionOutcome.UNCHANGED
        }
        scheduler.cancel(occurrence.id)
        val done = occurrence.copy(
            status = OccurrenceStatus.COMPLETED,
            completedAt = now,
            nextReminderAt = null,
        )
        occurrenceDao.upsert(done.toEntity())
        spawnNextIfNeeded(series, done.localDate, now)
        ActionOutcome.APPLIED
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

    suspend fun deleteOccurrence(occurrenceId: String): ActionOutcome = writer.withLock {
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock ActionOutcome.GONE
        scheduler.cancel(occurrenceId)
        val series = seriesDao.get(row.seriesId)?.toTaskSeries()
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
            return@withLock ActionOutcome.APPLIED
        }
        // Sem o tombstone a rotina de avanço rematerializa a data apagada no próximo start —
        // e isso vale também para a tarefa única: `RecurrenceEngine.firstOnOrAfter` a
        // rematerializa em `startLocalDate` enquanto ele não estiver no passado, que é
        // justamente onde ela fica depois de uma edição. Apagar a série junto levaria no
        // CASCADE a linha COMPLETED que sobrou, o registro do que ela fez.
        if (series == null) {
            occurrenceDao.applyBatch(
                seriesDao = seriesDao,
                upserts = emptyList(),
                deletes = listOf(occurrenceId),
                series = null,
                deleteSeriesRow = false,
            )
            return@withLock ActionOutcome.APPLIED
        }
        val now = clock.instant()
        val skipped = OccurrenceLifecycle.skipDate(
            series.skippedDates,
            row.toDomain().localDate,
            OccurrenceLifecycle.todayIn(clock.zoneId(), now),
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
        ActionOutcome.APPLIED
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
        // Desfazer não zera o aviso que já saiu: a linha que volta é a MESMA
        // (`seriesId:localDate`), e `materialize` nasce com `lastReminderAt` nulo. Zerado, o
        // cartão migrava para "Não consegui avisar" — o aplicativo assumindo uma falha que
        // foi dela, que foi avisada e não fez (ver `missedSections`).
        //
        // A decisão de qual ocorrência a data que volta grava é a mesma da edição
        // (`occurrencesForChoice`), de propósito: as duas perguntam o que nasce de uma data
        // escolhida. Desfazer o Excluir de uma tarefa que repete com a data de hoje já passada
        // não pode rearmar o instante vencido — o `AlarmManager` dispara na hora; a ocorrência
        // de hoje vira não realizada e a próxima da regra é que fica pendente.
        val existing = occurrenceDao.forSeries(series.id).map { it.toDomain() }
        val choice = occurrencesForChoice(series, item.occurrence.localDate, item.occurrence, existing, now)
        val scheduled = choice.armed?.let { scheduler.schedule(it, series, first = true) }
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = listOfNotNull(
                choice.expired?.toEntity(),
                choice.armed?.copy(inexactAlarm = scheduled?.inexact ?: false)?.toEntity(),
            ),
            deletes = emptyList(),
            series = series.toEntity(),
            deleteSeriesRow = false,
        )
    }

    suspend fun endSeries(seriesId: String): ActionOutcome = writer.withLock {
        val now = clock.instant()
        val series = seriesDao.get(seriesId)?.toTaskSeries() ?: return@withLock ActionOutcome.GONE
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
        ActionOutcome.APPLIED
    }

    /**
     * Grava a edição de uma ocorrência e diz se gravou.
     *
     * A ocorrência (ou a série dela) pode ter saído do banco enquanto a tela de edição estava
     * aberta: aí a gravação é um no-op, e voltar como sucesso fazia a tela anunciar "Salvo"
     * para o que a usuária digitou sem que nada tivesse sido gravado — o texto dela se
     * perderia em silêncio. [EditOutcome.GONE] é o que a tela usa para dizer isso a ela.
     */
    suspend fun editOccurrence(
        occurrenceId: String,
        title: String,
        date: java.time.LocalDate,
        time: java.time.LocalTime,
        recurrence: com.theopadilha.falaagenda.domain.model.RecurrenceRule,
        amountCents: Long? = null,
        observation: String = "",
    ): EditOutcome = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock EditOutcome.GONE
        val series = seriesDao.get(row.seriesId)?.toTaskSeries() ?: return@withLock EditOutcome.GONE
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
            return@withLock EditOutcome.SAVED
        }
        val existing = occurrenceDao.forSeries(series.id).map { it.toDomain() }
        val pending = existing.filter { it.status == OccurrenceStatus.PENDING }
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
        val serieRow = updatedSeries.toEntity()
        val substituidas = pending.map { it.id }
        // A edição honra a data que ela tocou, mas instante vencido nunca fica pendente com
        // alarme — `occurrencesForChoice` arquiva a data vencida como não realizada e, na regra
        // que repete, arma a próxima. Era aqui que editar "todo dia às 08:00" para as 18:00 de
        // hoje, às 20:00, armava o alarme no passado e o `AlarmManager` disparava no ato da
        // edição. A mesma peça do "Desfazer" do Excluir (`restore`), de propósito: as duas
        // perguntam a mesma coisa.
        val choice = occurrencesForChoice(
            series = updatedSeries,
            chosenDate = date,
            previous = original,
            existing = existing,
            now = now,
        )
        val scheduled = choice.armed?.let { scheduler.schedule(it, updatedSeries, first = true) }
        occurrenceDao.applyBatch(
            seriesDao = seriesDao,
            upserts = listOfNotNull(
                choice.expired?.toEntity(),
                choice.armed?.copy(inexactAlarm = scheduled?.inexact ?: false)?.toEntity(),
            ),
            deletes = substituidas,
            series = serieRow,
            deleteSeriesRow = false,
        )
        // O preview é sobre o que vem depois: ancorado na data editada, editar uma data
        // passada criava três datas vencidas, o próximo avanço marcava todas como não
        // realizadas e a agenda ficava sem as futuras até o app reabrir.
        spawnUpcomingPreview(updatedSeries, OccurrenceLifecycle.todayIn(clock.zoneId(), now))
        EditOutcome.SAVED
    }

    suspend fun retryMissed(occurrenceId: String): RetryResult? = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock null
        val series = seriesDao.get(row.seriesId)?.toTaskSeries() ?: return@withLock null
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

    suspend fun snooze(occurrenceId: String, minutes: Long = 30): ActionOutcome = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock ActionOutcome.GONE
        val series = seriesDao.get(row.seriesId)?.toTaskSeries() ?: return@withLock ActionOutcome.GONE
        val occurrence = row.toDomain()
        // Não é o "já estava nesse estado" do [ActionOutcome.UNCHANGED]: nada é agendado aqui,
        // e a tela anuncia para quando o aviso vai tocar. Calar deixaria ela esperando por um
        // aviso que não existe — e, no caso do remédio, sem o remédio.
        if (occurrence.status != OccurrenceStatus.PENDING) return@withLock ActionOutcome.GONE
        val quiet = scheduler.quietHours()
        val plan = ReminderPolicy.snooze(now, minutes, series.zoneId, quiet, respectQuietHours = false)
        val updated = occurrence.copy(
            snoozedUntil = plan.fireAt,
            nextReminderAt = plan.fireAt,
            reminderStep = plan.step,
        )
        val scheduled = scheduler.schedule(updated, series, first = false)
        occurrenceDao.upsert(updated.copy(inexactAlarm = scheduled.inexact).toEntity())
        ActionOutcome.APPLIED
    }

    /**
     * [deliver] é quem mostra o aviso para ela — o repositório não conhece notificação, mas
     * precisa do desfecho para saber se o degrau da escada foi gasto. A chamada acontece
     * dentro do mesmo `writer` de quem decide o degrau: decidir e entregar são o mesmo ato,
     * sem um estado intermediário que outra escrita possa ler entre um e outro.
     */
    suspend fun onAlarmFired(
        occurrenceId: String,
        deliver: suspend (title: String, seriesId: String) -> Delivery,
    ) = writer.withLock {
        val now = clock.instant()
        val row = occurrenceDao.get(occurrenceId) ?: return@withLock
        val series = seriesDao.get(row.seriesId)?.toTaskSeries() ?: return@withLock
        val occurrence = row.toDomain()
        // O disparo é resolvido antes da varredura de ciclo de vida: a varredura marcaria
        // como não realizada a ocorrência de ontem cujo aviso está tocando neste instante
        // (adiado pela noite, ele só chega no fim do silêncio) e o lembrete morreria
        // exatamente quando devia soar.
        if (occurrence.status == OccurrenceStatus.PENDING && !series.isEnded) {
            fire(occurrence, series, now, deliver)
        } else {
            scheduler.cancel(occurrenceId)
        }
        applyLifecycle(series, now)
    }

    private suspend fun fire(
        occurrence: TaskOccurrence,
        series: TaskSeries,
        now: Instant,
        deliver: suspend (title: String, seriesId: String) -> Delivery,
    ) {
        val quiet = scheduler.quietHours()
        if (occurrence.reminderStep > 0 && ReminderPolicy.isInQuietHours(now, series.zoneId, quiet)) {
            val resume = ReminderPolicy.shiftOutOfQuietHours(now, series.zoneId, quiet)
            val deferred = occurrence.copy(nextReminderAt = resume)
            val scheduled = scheduler.schedule(deferred, series, first = false)
            occurrenceDao.upsert(deferred.copy(inexactAlarm = scheduled.inexact).toEntity())
            return
        }
        // O degrau só é gasto quando o aviso chega até ela. Antes a escada avançava aqui e só
        // depois o receiver tentava mostrar a notificação: com o aviso bloqueado (permissão
        // negada, canal desligado, sistema recusando), ela perdia a hora marcada E o degrau
        // seguinte — calada, e sem chance de o próximo tocar.
        //
        // O que fazer sem entrega depende do desfecho, e quem traduz notificação para [Delivery]
        // é o receiver: aqui não se conhece canal nem permissão.
        when (deliver(series.title, series.id)) {
            Delivery.ARRIVED -> Unit
            // Transitório: o sistema recusou agora, pode aceitar daqui a cinco minutos. A
            // ocorrência volta à fila com o mesmo degrau; quem encerra a insistência é a virada
            // do dia, que a marca como não realizada e cancela o alarme.
            Delivery.FAILED -> {
                scheduler.scheduleRecovery(occurrence.id, now.plusSeconds(DELIVERY_RETRY_DELAY_SECONDS))
                return
            }
            // Permanente: permissão negada ou canal desligado. Insistir não muda nada disso — a
            // chave é dela, e o estado só volta se ela mexer. Repetir de cinco em cinco minutos
            // não teria como dar certo e só acordaria o processo e gastaria binder no aparelho
            // dela, por causa de um botão que ela desligou sem querer. Nada é reagendado aqui: a
            // ocorrência fica pendente com a hora marcada.
            //
            // O aviso não morreu, porém. Enquanto a janela estiver aberta, o `rescheduleAll` de
            // todo start rearma a ocorrência no primeiro degrau — `reminderStep` zerado e
            // `lastReminderAt` nulo mandam `first = true`, com o instante marcado já no passado —
            // e o lembrete toca atrasado quando ela abre o aplicativo. Passada a janela, a
            // varredura a marca como não realizada (o terminador). Quem conta a ela que os avisos
            // estavam desligados é o cartão de avisos da home.
            Delivery.BLOCKED -> return
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
        // Primeiro de tudo, e antes de qualquer leitura, porque é o único gatilho da virada do
        // dia: `DATE_CHANGED` não é exceção do broadcast implícito desde o Android 8 (o app tem
        // targetSdk 36) e só `TIME_SET`, `TIMEZONE_CHANGED` e `NEXT_ALARM_CLOCK_CHANGED` chegam,
        // então quem vira o dia é este alarme — rearmado aqui, no start do processo, no boot, na
        // troca de hora e pela própria virada. Não depende de ocorrência nenhuma, e uma leitura
        // que estoure no meio do caminho (`SQLiteDiskIOException` é condição conhecida desta
        // base) não pode deixar o dia seguinte sem varredura: os dois chamadores engolem a
        // exceção com log, e o dia não viraria até ela abrir o aplicativo.
        scheduler.scheduleDailySweep(OccurrenceLifecycle.nextDaySweep(now, clock.zoneId()))
        val seriesList = seriesDao.getAll().map { it.toTaskSeries() }
        seriesList.forEach { series ->
            applyLifecycle(series, now)
        }
        // As pendentes, não o histórico inteiro: `getAll` trazia toda ocorrência já gravada
        // desde o começo do aplicativo (sem poda) para descartar tudo que não fosse PENDING.
        // E a série de cada uma sai do retrato que a varredura já leu — antes era um `get`
        // por ocorrência, o N+1 que a agenda de anos dela pagava em todo start.
        val seriesById = seriesList.associateBy { it.id }
        occurrenceDao.byStatus(OccurrenceStatus.PENDING.name).map { it.toDomain() }
            .forEach { occ ->
                val series = seriesById[occ.seriesId] ?: return@forEach
                // Instante marcado no passado só volta ao alarme enquanto for entrega
                // pendente: ver `OccurrenceLifecycle.valeRearmar`.
                if (!OccurrenceLifecycle.valeRearmar(occ, now, JANELA_ENTREGA_PENDENTE)) {
                    return@forEach
                }
                val scheduled = scheduler.schedule(
                    occ,
                    series,
                    first = occ.reminderStep == 0 && occ.lastReminderAt == null,
                )
                occurrenceDao.upsert(occ.copy(inexactAlarm = scheduled.inexact).toEntity())
            }
    }

    /**
     * O que uma escrita manual grava para a data que a usuária escolheu: a ocorrência que fica
     * pendente e recebe o alarme, e a que já venceu e fica arquivada como não realizada.
     *
     * Compartilhada de propósito entre a edição (`editOccurrence`) e o "Desfazer" do Excluir
     * (`restore`), porque as duas perguntam a mesma coisa — qual ocorrência nasce da data
     * escolhida. Cada uma tinha a sua cópia, e cada cópia perdia um ramo: a edição só cobria a
     * regra que não repete e o desfazer rearmava a data literal mesmo vencida. Cópias da mesma
     * decisão divergem; uma só não.
     *
     * Instante vencido nunca fica pendente com alarme: o `AlarmManager` dispara na hora para um
     * instante no passado. A escolha que já passou vira não realizada, e a regra que repete
     * segue na próxima data dela — é o que "todo dia às 18:00" significa. A que não repete não
     * tem próxima: fica só com a não realizada, sem alarme nenhum.
     */
    private fun occurrencesForChoice(
        series: TaskSeries,
        chosenDate: LocalDate,
        previous: TaskOccurrence?,
        existing: List<TaskOccurrence>,
        now: Instant,
    ): ChoiceOccurrences {
        val plan = ChoiceSchedule.plan(
            rule = series.recurrence,
            chosenDate = chosenDate,
            chosenTime = series.localTime,
            zoneId = series.zoneId,
            now = now,
        )
        // Mesma data é a MESMA linha (`seriesId:localDate`): o aviso que já saiu não deixa de
        // ter saído porque ela corrigiu o horário. Data nova é id novo, e aí não há aviso para
        // herdar (ver `missedSections`).
        val inherited = previous?.takeIf { it.localDate == chosenDate }?.lastReminderAt
        val chosen = OccurrenceLifecycle.materialize(series, chosenDate, now)
            .copy(lastReminderAt = inherited)
        if (!plan.expired) return ChoiceOccurrences(armed = chosen, expired = null)
        val expired = chosen.copy(
            status = OccurrenceStatus.MISSED,
            missedAt = now,
            nextReminderAt = null,
        )
        // A escolha venceu e a regra não repete: não há próxima, e nenhum alarme é armado.
        if (!series.recurrence.isRecurring) return ChoiceOccurrences(armed = null, expired = expired)
        // Data excluída pela usuária não volta a nascer: o tombstone vale aqui como vale em
        // `advance` e no preview. Sem esta guarda, editar o horário do cartão de hoje (ou
        // desfazer outro Excluir) rematerializava a data que ela tinha excluído e rearmava o
        // alarme dela, com o tombstone ainda gravado.
        if (series.isSkipped(plan.date)) return ChoiceOccurrences(armed = null, expired = expired)
        val nextId = OccurrenceIds.of(series.id, plan.date)
        // Ocorrência já viva não é reaberta: concluída ou não realizada na próxima data fica
        // como está, e só uma PENDING é que é rearmada — e aí com o progresso que ela já tinha
        // (mesmo critério de `advance`, que passa a existente para `materialize`).
        val nextExisting = existing.firstOrNull { it.id == nextId }
        val next = when {
            nextExisting == null -> OccurrenceLifecycle.materialize(series, plan.date, now)
            nextExisting.status != OccurrenceStatus.PENDING -> null
            else -> OccurrenceLifecycle.materialize(series, plan.date, now, existing = nextExisting)
        }
        // `materialize` com `existing` preserva o `nextReminderAt` que a linha já tinha, e um
        // aviso adiado ou não entregue pode estar vencido — entregue assim ao `AlarmManager`,
        // ele dispara na hora. O instante que esta peça devolve é o que a escrita entrega ao
        // alarme, então vencido aqui não passa: cai no materialize limpo, que marca o aviso no
        // horário da própria ocorrência (futuro, porque `plan.date` é sempre depois de hoje
        // neste ramo).
        val armed = if (next != null && next.nextReminderAt?.isBefore(now) == true) {
            OccurrenceLifecycle.materialize(series, plan.date, now)
        } else {
            next
        }
        return ChoiceOccurrences(armed = armed, expired = expired)
    }

    private suspend fun applyLifecycle(series: TaskSeries, now: Instant) {
        // O "hoje" aqui é o mesmo que parte as seções da agenda (`clock.today()`): com dois
        // fusos em jogo a tela listava em "Hoje" uma ocorrência que o ciclo de vida já tinha
        // dado como não realizada.
        val today = OccurrenceLifecycle.todayIn(clock.zoneId(), now)
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
     * A regra é do ciclo de vida (`OccurrenceLifecycle.entregaPendente`); aqui ela só entra na
     * janela de entrega deste aplicativo. Dentro da janela o `rescheduleAll` rearma o aviso e o
     * `advance` não o arquiva; passada ela, o `advance` volta a decidir e a ocorrência vira não
     * realizada, que é o terminador da entrega pendente (nada fica pendurado para sempre).
     */
    private fun entregaPendente(occurrence: TaskOccurrence, now: Instant): Boolean =
        OccurrenceLifecycle.entregaPendente(occurrence, now, JANELA_ENTREGA_PENDENTE)

    /**
     * Grava uma ocorrência recém-materializada e arma o alarme dela — mas só se o instante ainda
     * não passou. Entregar ao `AlarmManager` um instante vencido é disparo imediato, e é o
     * invariante do repositório inteiro: nenhum caminho arma alarme no passado.
     *
     * O `valeRearmar` da varredura não serve para decidir isto: ele trata instante vencido com
     * `lastReminderAt` nulo como entrega pendente — o aviso que o Doze segurou e ainda vai tocar
     * —, e uma ocorrência que acabou de nascer nunca foi armada, então não há entrega para
     * esperar. Vencida, ela fica na agenda pendente e sem aviso marcado (`nextReminderAt` nulo),
     * e quem a encerra é a virada do dia.
     *
     * Era por aqui que o invariante ainda vazava: o preview que roda depois da edição recriava a
     * data de hoje (que a edição tinha acabado de arquivar) e a armava em 08:00 com o relógio já
     * em 10:00 — o celular dela apitava no ato de salvar.
     */
    private suspend fun storeAndArm(occurrence: TaskOccurrence, series: TaskSeries, now: Instant) {
        if (occurrence.scheduledAt.isBefore(now)) {
            occurrenceDao.upsert(occurrence.copy(nextReminderAt = null).toEntity())
            return
        }
        val scheduled = scheduler.schedule(occurrence, series, first = true)
        occurrenceDao.upsert(occurrence.copy(inexactAlarm = scheduled.inexact).toEntity())
    }

    private suspend fun spawnNextIfNeeded(series: TaskSeries, completedDate: LocalDate, now: Instant) {
        if (series.isEnded || !series.recurrence.isRecurring) return
        val nextDate = RecurrenceEngine.nextAfter(series.recurrence, series.startLocalDate, completedDate) ?: return
        if (series.isSkipped(nextDate)) return
        val existing = occurrenceDao.get(OccurrenceIds.of(series.id, nextDate))
        if (existing != null) return
        storeAndArm(OccurrenceLifecycle.materialize(series, nextDate, now), series, now)
    }

    private suspend fun spawnUpcomingPreview(series: TaskSeries, today: LocalDate) {
        if (series.isEnded || !series.recurrence.isRecurring) return
        val now = clock.instant()
        RecurrenceEngine.upcoming(series.recurrence, series.startLocalDate, today, 3).forEach { date ->
            // Sem esta checagem o preview desfaz o tombstone: `upcoming` começa em hoje,
            // então a data que o usuário acabou de excluir era a primeira da lista.
            if (series.isSkipped(date)) return@forEach
            val id = OccurrenceIds.of(series.id, date)
            if (occurrenceDao.get(id) == null) {
                storeAndArm(OccurrenceLifecycle.materialize(series, date, now), series, now)
            }
        }
    }

    companion object {
        /** Curto de propósito: recuperação não pode virar trabalho sem limite. */
        const val RECOVERY_DELAY_SECONDS = 60L

        /**
         * Quanto esperar antes de tentar de novo o aviso que não chegou até ela por uma falha
         * transitória — o sistema recusando a notificação agora. Curto o bastante para o
         * lembrete do horário ainda valer como lembrete; longo o bastante para a insistência
         * não virar tempestade de disparos. Quem a encerra é a virada do dia, quando a
         * varredura marca a ocorrência como não realizada. O aviso [Delivery.BLOCKED] nem
         * chega aqui: ver [TaskRepository.fire].
         */
        const val DELIVERY_RETRY_DELAY_SECONDS = 300L

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

/**
 * O que uma escolha manual de data (a que ela tocou na tela) vira depois de salva, nos caminhos
 * de escrita manual — a edição (`TaskRepository.editOccurrence`) e o "Desfazer" do Excluir
 * (`TaskRepository.restore`).
 *
 * A decisão é uma só, e compartilhada de propósito: o repositório a usa para gravar e a tela
 * (`AgendaFormat.promiseOfChoice`, `HomeViewModel.announceOfEdit`) para descrever o desfecho.
 * O defeito de origem foram cópias da mesma decisão divergindo — a tela prometia a data literal
 * e o repositório arquivava essa data e armava a próxima.
 *
 * Invariante: a ocorrência que recebe alarme nunca cai num instante vencido — um alarme no
 * passado o `AlarmManager` dispara na hora. A escolha que venceu fica arquivada como não
 * realizada e, na regra que repete, a próxima data dela é que é armada — é o que "todo dia às
 * 18:00" significa. A regra que não repete não tem próxima: fica só a não realizada, sem aviso.
 */
object ChoiceSchedule {
    /**
     * [date] é a data que fica pendente — a que recebe o alarme na regra que repete, e a própria
     * escolhida quando ela ainda não venceu. [expired] diz que a escolha venceu: nesse caso a
     * data escolhida é arquivada, e na regra que não repete [date] é ela mesma, sem alarme
     * nenhum (não há próxima).
     */
    data class Planned(
        val date: LocalDate,
        val expired: Boolean,
    )

    fun plan(
        rule: RecurrenceRule,
        chosenDate: LocalDate,
        chosenTime: LocalTime,
        zoneId: ZoneId,
        now: Instant,
    ): Planned {
        val chosenAt = chosenDate.atTime(chosenTime).atZone(zoneId).toInstant()
        if (!chosenAt.isBefore(now)) return Planned(chosenDate, expired = false)
        // Mesma peça da criação: a escolha é piso e a regra move. Um passo só da regra não
        // bastaria — com a data escolhida já no passado ele também nasceria vencido e o alarme
        // dispararia na hora de novo. Na regra que não repete o piso vale, e a data devolvida é a
        // própria escolhida: quem decide que ela não tem aviso é o `isRecurring` de quem chama.
        val next = DraftSchedule.firstOccurrence(rule, chosenDate, chosenTime, zoneId, now).date
        return Planned(next, expired = true)
    }
}

/**
 * O que uma escolha de data vira numa escrita manual — ver `TaskRepository.occurrencesForChoice`.
 * [armed] é a ocorrência que recebe o alarme (nula quando não há nenhuma pendente a armar: a
 * escolha vencida que não repete, ou uma próxima que já existe e não está pendente). [expired] é
 * a escolha que venceu, arquivada como não realizada, e só existe nesse caso.
 */
data class ChoiceOccurrences(
    val armed: TaskOccurrence?,
    val expired: TaskOccurrence?,
)

/**
 * Como terminou a tentativa de mostrar o aviso, do ponto de vista de quem decide a escada.
 * Quem traduz notificação para isto é o receiver — ver [TaskRepository.onAlarmFired]: o
 * repositório não conhece canal nem permissão, e é o desfecho que diz o que fazer com o
 * degrau.
 */
enum class Delivery {
    /** O aviso chegou até ela: o degrau é gasto e a repetição seguinte é armada. */
    ARRIVED,

    /**
     * Bloqueio permanente: permissão negada ou canal desligado. Não é uma tentativa que possa
     * dar certo daqui a cinco minutos, então nada é reagendado — a ocorrência fica pendente com
     * a hora marcada e a varredura da virada do dia a marca como não realizada.
     */
    BLOCKED,

    /** Falha transitória (o sistema recusou a notificação agora): a ocorrência volta à fila. */
    FAILED,
}

data class RetryResult(
    val date: java.time.LocalDate,
    val time: java.time.LocalTime,
)

/** Ver [TaskRepository.editOccurrence]: a diferença entre ter gravado e não ter o que gravar. */
enum class EditOutcome {
    /** A ocorrência estava no banco e a mudança foi gravada. */
    SAVED,

    /** A ocorrência (ou a série dela) não está mais no banco: nada foi gravado. */
    GONE,
}

/**
 * O desfecho de uma ação sobre uma ocorrência — concluir, excluir, encerrar a série, adiar.
 * [EditOutcome] não serve aqui porque só tem dois casos, e o do meio é justamente o que
 * separa a mentira do alarme falso.
 *
 * [UNCHANGED] é o que já estava no estado pedido (concluir uma ocorrência já concluída, por
 * exemplo): no-op legítimo, e quem chamou não anuncia nada. Repetir "Feito." é ruído; dizer
 * "não deu" seria falso.
 *
 * [GONE] é quando não havia o que gravar — a linha (ou a série) saiu do banco, ou a
 * ocorrência não aceita mais a ação. Aí a tela precisa dizer que não deu: o que ela tocou não
 * pegou, e no adiamento isso significa que nenhum aviso foi agendado.
 */
enum class ActionOutcome {
    /** A ocorrência estava lá e a ação foi gravada. */
    APPLIED,

    /** Já estava no estado pedido: não há o que gravar nem o que anunciar. */
    UNCHANGED,

    /** Não havia o que gravar: nada foi feito, e quem chamou precisa dizer isso a ela. */
    GONE,
}

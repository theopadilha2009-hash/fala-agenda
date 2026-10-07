package com.theopadilha.falaagenda.domain.recurrence

import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.reminder.ReminderPolicy
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class LifecycleChange(
    val upserts: List<TaskOccurrence> = emptyList(),
    val markMissed: List<TaskOccurrence> = emptyList(),
    val cancelAlarmsOf: List<String> = emptyList(),
)

object OccurrenceLifecycle {
    // atalho: tombstone guarda só os últimos 90 dias e no máximo 120 datas, sempre as mais
    // próximas de hoje; revisitar se editar/concluir ocorrência muito antiga voltar a
    // rematerializar uma data excluída
    const val SKIPPED_RETENTION_DAYS = 90L
    const val MAX_SKIPPED_DATES = 120

    /**
     * Quando a varredura da virada do dia toca. Cinco minutos depois da meia-noite de
     * propósito: o alarme não disputa o instante exato da virada com o sistema, e o dia
     * local já virou para quem for ler o relógio.
     */
    val DAY_SWEEP_AT: LocalTime = LocalTime.of(0, 5)

    fun scheduledInstant(series: TaskSeries, localDate: LocalDate): Instant =
        ZonedDateTime.of(localDate, series.localTime, series.zoneId).toInstant()

    /** Marca a data como excluída, descartando tombstones velhos demais para voltar a valer. */
    fun skipDate(
        skipped: Set<LocalDate>,
        localDate: LocalDate,
        todayInSeriesZone: LocalDate,
    ): Set<LocalDate> {
        val limite = todayInSeriesZone.minusDays(SKIPPED_RETENTION_DAYS)
        // O teto corta as datas mais DISTANTES de hoje: são as próximas que o preview/advance
        // vão materializar, e uma tombstone descartada aí faz a data excluída renascer.
        return (skipped + localDate)
            .filter { !it.isBefore(limite) }
            .sortedWith(
                compareBy<LocalDate> { kotlin.math.abs(ChronoUnit.DAYS.between(todayInSeriesZone, it)) }
                    .thenByDescending { it },
            )
            .take(MAX_SKIPPED_DATES)
            .toSet()
    }

    /** Desfaz a exclusão da data (usado quando o usuário desfaz ou remarca para o mesmo dia). */
    fun unskipDate(skipped: Set<LocalDate>, localDate: LocalDate): Set<LocalDate> =
        skipped - localDate

    fun materialize(
        series: TaskSeries,
        localDate: LocalDate,
        now: Instant,
        existing: TaskOccurrence? = null,
    ): TaskOccurrence {
        val scheduledAt = scheduledInstant(series, localDate)
        val first = ReminderPolicy.firstReminder(scheduledAt)
        if (existing != null) {
            val keepProgress = existing.status == OccurrenceStatus.PENDING &&
                (existing.lastReminderAt != null || existing.snoozedUntil != null || existing.reminderStep > 0)
            return existing.copy(
                scheduledAt = scheduledAt,
                nextReminderAt = when {
                    existing.status != OccurrenceStatus.PENDING -> existing.nextReminderAt
                    keepProgress -> existing.nextReminderAt
                    else -> first.fireAt
                },
            )
        }
        return TaskOccurrence(
            id = OccurrenceIds.of(series.id, localDate),
            seriesId = series.id,
            localDate = localDate,
            scheduledAt = scheduledAt,
            status = OccurrenceStatus.PENDING,
            reminderStep = ReminderPolicy.STEP_FIRST,
            nextReminderAt = first.fireAt,
        )
    }

    /**
     * Se a ocorrência corrente ainda está pendente quando a próxima nasce,
     * marca a anterior como não realizada, cancela cobrança e inicia a nova.
     *
     * [fusoDaSerieMudou] diz que o fuso do relógio de agora não é o que a série carrega gravado,
     * e é o que autoriza a varredura a reescrever o instante de uma ocorrência que não tem
     * progresso nenhum: o instante dela foi calculado no fuso velho e ficaria deslocado para
     * sempre (ver `TaskRepository.toTaskSeries`). Sem troca de fuso não há o que realinhar nela —
     * o horário dela é o que a edição de outra dose preservou de propósito.
     */
    fun advance(
        series: TaskSeries,
        existing: List<TaskOccurrence>,
        now: Instant,
        todayInSeriesZone: LocalDate,
        fusoDaSerieMudou: Boolean = false,
    ): LifecycleChange {
        if (series.isEnded) {
            val pending = existing.filter { it.status == OccurrenceStatus.PENDING }
            return LifecycleChange(
                markMissed = emptyList(),
                cancelAlarmsOf = pending.map { it.id },
                upserts = pending.map {
                    it.copy(
                        status = OccurrenceStatus.CANCELLED,
                        nextReminderAt = null,
                    )
                },
            )
        }

        val dueDate = RecurrenceEngine.firstOnOrAfter(
            series.recurrence,
            series.startLocalDate,
            todayInSeriesZone,
        )

        val byDate = existing.associateBy { it.localDate }
        val markMissed = mutableListOf<TaskOccurrence>()
        val upserts = mutableListOf<TaskOccurrence>()
        val cancel = mutableListOf<String>()

        // O lembrete adiado pelo horário de silêncio só toca às 08:00 do dia seguinte e o
        // adiamento do usuário pode cair depois da meia-noite: uma data vencida que ainda
        // tem aviso vivo é "vai tocar", não "não foi feita".
        existing.filter {
            it.status == OccurrenceStatus.PENDING && !temLembreteVivo(it, now) &&
                expirou(it, todayInSeriesZone, series.zoneId)
        }.forEach { stale ->
            val missed = stale.copy(
                status = OccurrenceStatus.MISSED,
                missedAt = now,
                nextReminderAt = null,
            )
            markMissed += missed
            upserts += missed
            cancel += stale.id
        }

        if (dueDate == null) {
            return LifecycleChange(
                upserts = upserts,
                markMissed = markMissed,
                cancelAlarmsOf = cancel,
            )
        }

        val currentExisting = byDate[dueDate]
        if (currentExisting == null) {
            // Data excluída pelo usuário não volta a nascer; a série segue no próximo dia.
            if (!series.isSkipped(dueDate)) upserts += materialize(series, dueDate, now)
        } else if (currentExisting.status == OccurrenceStatus.PENDING) {
            // A varredura não reescreve a ocorrência que ainda não tocou e não tem progresso
            // nenhum: não há o que realinhar nela, e o instante dela é o horário da série
            // **quando ela foi materializada**. Desde a edição de uma dose de outra data o
            // horário da série pode ter mudado sem que este dia devesse mudar junto — é o que
            // preserva a dose de hoje quando ela edita a de amanhã. Realinhar aqui é justamente
            // o que a fazia tocar 14:00 em silêncio no start seguinte, que é a mesma classe do
            // defeito de origem ("mudei o horário do remédio de amanhã e o de hoje parou de
            // tocar").
            //
            // A exceção é a troca de fuso do aparelho ([fusoDaSerieMudou]): o instante foi
            // calculado no fuso velho e a varredura é a única cura dele. Ela não é dedutível
            // daqui — o instante da ocorrência sozinho não diz com que fuso nem com que horário
            // da série ele foi escrito, e o que a série carrega gravado é o fuso do dia do
            // cadastro, que o repositório lê com o fuso de agora (ver `toTaskSeries`).
            //
            // Sem progresso não há instante herdado para honrar, e o resto da decisão da
            // varredura continua valendo: o instante vencido que não foi entregue não vai para o
            // alarme (ver `valeRearmar`, que é quem o descarta).
            val semProgresso = currentExisting.lastReminderAt == null &&
                currentExisting.snoozedUntil == null &&
                currentExisting.reminderStep == 0
            if (!semProgresso || fusoDaSerieMudou) {
                val refreshed = materialize(series, dueDate, now, currentExisting)
                if (refreshed != currentExisting) upserts += refreshed
            }
        }

        return LifecycleChange(
            upserts = upserts,
            markMissed = markMissed,
            cancelAlarmsOf = cancel,
        )
    }

    /**
     * Ainda vai tocar: lembrete (ou adiamento) marcado para agora ou depois. O instante
     * exato conta como vivo — é justamente quando o alarme está sendo entregue.
     */
    private fun temLembreteVivo(occurrence: TaskOccurrence, now: Instant): Boolean =
        listOfNotNull(occurrence.nextReminderAt, occurrence.snoozedUntil).any { !it.isBefore(now) }

    /**
     * Sem lembrete vivo, a ocorrência vira não realizada quando o dia do último aviso
     * entregue também passou: `max(localDate, dia do último lembrete) < hoje`.
     *
     * O aviso adiado pelo silêncio toca às 08:00 do dia seguinte e é o último degrau da
     * escada. Olhar só a data da ocorrência arquivaria a de ontem como não realizada no
     * exato instante em que o aviso toca — e o "Adiar" da notificação, que só age em
     * ocorrência pendente, viraria no-op. Nunca tendo aviso entregue, vale a própria data
     * da ocorrência: ela expira na primeira varredura, como sempre.
     */
    private fun expirou(
        occurrence: TaskOccurrence,
        todayInSeriesZone: LocalDate,
        zoneId: ZoneId,
    ): Boolean {
        val diaDoUltimoAviso = occurrence.lastReminderAt?.atZone(zoneId)?.toLocalDate()
        val referencia = diaDoUltimoAviso
            ?.let { maxOf(occurrence.localDate, it) }
            ?: occurrence.localDate
        return referencia.isBefore(todayInSeriesZone)
    }

    /**
     * A varredura pode entregar ao alarme o instante que esta ocorrência já tem marcado?
     *
     * Instante marcado para agora ou depois: sim, é o caso de sempre — start do processo,
     * boot, troca de hora, virada do dia.
     *
     * Instante marcado que já passou: só enquanto for [entregaPendente]. No `AlarmManager` um
     * instante no passado dispara na hora, então rearmá-lo é disparar agora — e a varredura
     * roda em toda abertura do aplicativo. A repetição das 09:30 que o Doze segurou, rearmada
     * às 16:00 pelo start seguinte, entregava o remédio da manhã de novo: cinco aberturas,
     * cinco avisos em rajada. Fora da janela, nada se perde: a ocorrência continua na agenda
     * e quem a encerra é a virada do dia, que a marca como não realizada ([expirou]).
     *
     * Dentro da janela o rearme continua de pé de propósito — é o que faz o aviso bloqueado
     * (permissão negada, canal desligado) tocar atrasado quando ela abre o aplicativo.
     * Ver `TaskRepository.fire`.
     */
    fun valeRearmar(
        occurrence: TaskOccurrence,
        now: Instant,
        janelaEntrega: Duration,
    ): Boolean {
        val marcado = occurrence.nextReminderAt ?: return true
        return !marcado.isBefore(now) || entregaPendente(occurrence, now, janelaEntrega)
    }

    /**
     * Alarme marcado para um instante que já passou e ainda não foi entregue é entrega
     * pendente. Nunca tendo tocado, a escada nem começou (`lastReminderAt` nulo): o aviso das
     * 22:00 que o Doze segurou continua pendente depois da meia-noite — sem isso a primeira
     * varredura do dia seguinte o arquivava como não realizada e o único aviso do dia morria
     * calado. Com um aviso já entregue, vale o critério de sempre: o que está marcado é a
     * repetição seguinte, e ela só é entrega pendente se o último aviso ficou para trás.
     *
     * Vale até [janela] depois do horário marcado — o atraso real de entrega (Doze, alarme
     * inexato, aparelho desligado) cabe aí. Passada a janela, o aviso não é ressuscitado: a
     * ocorrência volta a ser encerrada pela virada do dia.
     */
    fun entregaPendente(
        occurrence: TaskOccurrence,
        now: Instant,
        janela: Duration,
    ): Boolean {
        val marcado = occurrence.nextReminderAt ?: return false
        if (marcado.isAfter(now)) return false
        val ultimoAviso = occurrence.lastReminderAt
        return (ultimoAviso == null || ultimoAviso.isBefore(marcado)) &&
            now.isBefore(marcado.plus(janela))
    }

    /**
     * O próximo instante em que a varredura da virada do dia toca: [DAY_SWEEP_AT] local, hoje
     * se ainda não passou, senão amanhã.
     *
     * Existe porque o dia não vira sozinho: `android.intent.action.DATE_CHANGED` não está na
     * lista de exceções do broadcast implícito do Android 8, então receiver de manifesto
     * nunca o recebe. Quem acorda a varredura é este alarme, rearmado a cada varredura.
     */
    fun nextDaySweep(now: Instant, zoneId: ZoneId): Instant {
        val zoned = now.atZone(zoneId)
        val hoje = zoned.toLocalDate().atTime(DAY_SWEEP_AT).atZone(zoneId)
        // `plusDays` é dia de calendário: na virada de horário de verão o alarme continua
        // caindo em 00:05 local, e não 24 h depois.
        return if (hoje.toInstant().isAfter(now)) hoje.toInstant() else hoje.plusDays(1).toInstant()
    }

    fun todayIn(zoneId: ZoneId, now: Instant): LocalDate = now.atZone(zoneId).toLocalDate()
}

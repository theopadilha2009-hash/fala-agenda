package com.theopadilha.falaagenda.domain.recurrence

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.reminder.ReminderPolicy
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

class OccurrenceLifecycleTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = Instant.parse("2026-09-27T11:00:00Z")

    private fun series(
        start: LocalDate = LocalDate.of(2026, 9, 20),
        skipped: Set<LocalDate> = emptySet(),
    ) = TaskSeries(
        id = "s1",
        title = "Remédio",
        zoneId = zone,
        localTime = LocalTime.of(8, 0),
        startLocalDate = start,
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        skippedDates = skipped,
        createdAt = Instant.parse("2026-09-20T11:00:00Z"),
        updatedAt = Instant.parse("2026-09-20T11:00:00Z"),
    )

    @Test
    fun advanceMaterializaODiaCorrente() {
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = emptyList(),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.upserts.map { it.localDate }).containsExactly(LocalDate.of(2026, 9, 27))
    }

    @Test
    fun advanceNaoRematerializaDiaExcluido() {
        val change = OccurrenceLifecycle.advance(
            series = series(skipped = setOf(LocalDate.of(2026, 9, 27))),
            existing = emptyList(),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.upserts).isEmpty()
        assertThat(change.cancelAlarmsOf).isEmpty()
    }

    @Test
    fun advanceVoltaAMaterializarNoDiaSeguinte() {
        val change = OccurrenceLifecycle.advance(
            series = series(skipped = setOf(LocalDate.of(2026, 9, 27))),
            existing = emptyList(),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 28),
        )
        assertThat(change.upserts.map { it.localDate }).containsExactly(LocalDate.of(2026, 9, 28))
    }

    /**
     * Lembrete adiado pelo horário de silêncio só toca às 08:00 do dia seguinte. A
     * varredura não pode dar a ocorrência de ontem como não realizada enquanto o
     * aviso dela ainda está marcado para tocar.
     */
    @Test
    fun ocorrenciaDeOntemComLembreteFuturoContinuaPendente() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(ontem, nextReminderAt = now.plusSeconds(3 * 3600))
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed).isEmpty()
        assertThat(change.cancelAlarmsOf).isEmpty()
    }

    /** O lembrete pode estar marcado no próprio instante da varredura: ainda vai tocar. */
    @Test
    fun ocorrenciaDeOntemComLembreteNoInstanteAtualContinuaPendente() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(ontem, nextReminderAt = now)
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed).isEmpty()
    }

    /** Adiamento é ação explícita do usuário e vale a mesma proteção do lembrete. */
    @Test
    fun ocorrenciaDeOntemComSnoozeNoFuturoContinuaPendente() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(ontem, nextReminderAt = null, snoozedUntil = now.plusSeconds(600))
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed).isEmpty()
        assertThat(change.cancelAlarmsOf).isEmpty()
    }

    /** Sem nada marcado para tocar, a data vencida vira não realizada como antes. */
    @Test
    fun ocorrenciaDeOntemSemLembreteFuturoViraNaoRealizada() {
        val ontem = LocalDate.of(2026, 9, 26)
        val vencida = occurrence(ontem, nextReminderAt = now.minusSeconds(3600))
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(vencida),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed.map { it.id }).containsExactly(vencida.id)
        assertThat(change.markMissed.single().status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(change.cancelAlarmsOf).containsExactly(vencida.id)
    }

    /**
     * O adiamento do silêncio entrega o último aviso do dia às 08:00 do dia seguinte e a
     * escada então termina. No instante do disparo o dia da ocorrência já passou, mas o dia
     * do aviso é hoje: arquivá-la como não realizada ali é o que matava o "Adiar" da própria
     * notificação, que só age em ocorrência pendente.
     */
    @Test
    fun ocorrenciaComAvisoEntregueHojeContinuaPendente() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(
            ontem,
            nextReminderAt = null,
            lastReminderAt = Instant.parse("2026-09-27T11:00:00Z"),
        )
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed).isEmpty()
        assertThat(change.cancelAlarmsOf).isEmpty()
    }

    /** Passado também o dia do último aviso entregue, a ocorrência vira não realizada. */
    @Test
    fun ocorrenciaComAvisoEntregueOntemViraNaoRealizada() {
        val ontem = LocalDate.of(2026, 9, 26)
        val adiada = occurrence(
            ontem,
            nextReminderAt = null,
            lastReminderAt = Instant.parse("2026-09-27T11:00:00Z"),
        )
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(adiada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 28),
        )
        assertThat(change.markMissed.map { it.id }).containsExactly(adiada.id)
        assertThat(change.cancelAlarmsOf).containsExactly(adiada.id)
    }

    /**
     * Teto da escada: o último aviso foi entregue ontem e a escada encerrou em silêncio. A
     * ocorrência não pode ficar pendurada para sempre — expira na virada do dia.
     */
    @Test
    fun ocorrenciaQueEsgotouAEscadaOntemViraNaoRealizada() {
        val anteontem = LocalDate.of(2026, 9, 25)
        val esgotada = occurrence(
            anteontem,
            nextReminderAt = null,
            lastReminderAt = Instant.parse("2026-09-26T11:00:00Z"),
        )
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(esgotada),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed.map { it.id }).containsExactly(esgotada.id)
    }

    /** Nunca teve aviso entregue: vira não realizada na primeira varredura, como sempre. */
    @Test
    fun ocorrenciaAntigaSemAvisoEntregueViraNaoRealizada() {
        val antiga = LocalDate.of(2026, 9, 20)
        val vencida = occurrence(antiga, nextReminderAt = null, lastReminderAt = null)
        val change = OccurrenceLifecycle.advance(
            series = series(),
            existing = listOf(vencida),
            now = now,
            todayInSeriesZone = LocalDate.of(2026, 9, 27),
        )
        assertThat(change.markMissed.map { it.id }).containsExactly(vencida.id)
    }

    /**
     * Um instante marcado no passado dispara na hora no `AlarmManager`, e a varredura roda em
     * toda abertura do aplicativo: rearmá-lo era uma notificação por abertura — o remédio da
     * manhã chegando em rajada à tarde. Fora da janela de entrega pendente, a varredura deixa
     * o instante vencido quieto; a ocorrência continua na agenda e quem a encerra é a virada
     * do dia.
     */
    @Test
    fun naoRearmaInstanteVencidoForaDaJanela() {
        val hoje = LocalDate.of(2026, 9, 27)
        val vencida = occurrence(
            hoje,
            nextReminderAt = now.minusSeconds(7 * 3600),
            lastReminderAt = now.minusSeconds(8 * 3600),
        )

        assertThat(OccurrenceLifecycle.valeRearmar(vencida, now, Duration.ofHours(6))).isFalse()
    }

    /** Dentro da janela o rearme é o que faz o aviso bloqueado tocar atrasado. */
    @Test
    fun rearmaInstanteVencidoDentroDaJanela() {
        val hoje = LocalDate.of(2026, 9, 27)
        val atrasada = occurrence(hoje, nextReminderAt = now.minusSeconds(3600))

        assertThat(OccurrenceLifecycle.entregaPendente(atrasada, now, Duration.ofHours(6))).isTrue()
        assertThat(OccurrenceLifecycle.valeRearmar(atrasada, now, Duration.ofHours(6))).isTrue()
    }

    /** Instante marcado à frente é o caso de sempre: boot, troca de hora, start. */
    @Test
    fun rearmaInstanteFuturo() {
        val hoje = LocalDate.of(2026, 9, 27)
        val futura = occurrence(hoje, nextReminderAt = now.plusSeconds(3600))

        assertThat(OccurrenceLifecycle.valeRearmar(futura, now, Duration.ofHours(6))).isTrue()
    }

    /** A escada encerrada (sem instante marcado) não tem o que rearmar. */
    @Test
    fun semInstanteMarcadoNaoHaEntregaPendente() {
        val hoje = LocalDate.of(2026, 9, 27)
        val semNada = occurrence(hoje, nextReminderAt = null)

        assertThat(OccurrenceLifecycle.entregaPendente(semNada, now, Duration.ofHours(6))).isFalse()
        assertThat(OccurrenceLifecycle.valeRearmar(semNada, now, Duration.ofHours(6))).isTrue()
    }

    /**
     * O alarme da virada do dia cai no próximo 00:05 local. É ele que faz o dia virar: sem
     * broadcast que chegue (`DATE_CHANGED` não é exceção do broadcast implícito desde o
     * Android 8), a ocorrência de ontem ficaria pendente para sempre.
     */
    @Test
    fun nextDaySweepCaiNoProximoCincoDaMadrugada() {
        // 11:00 UTC é 08:00 em São Paulo: o próximo 00:05 é o de amanhã.
        val agora = Instant.parse("2026-09-27T11:00:00Z")

        val proximo = OccurrenceLifecycle.nextDaySweep(agora, zone)

        assertThat(proximo).isEqualTo(Instant.parse("2026-09-28T03:05:00Z"))
    }

    /** Armado depois da meia-noite (ou às 00:05 em ponto), o alarme é o de amanhã. */
    @Test
    fun nextDaySweepDepoisDaMeiaNoiteVaiParaOAmanha() {
        val madrugada = Instant.parse("2026-09-27T03:06:00Z")

        val proximo = OccurrenceLifecycle.nextDaySweep(madrugada, zone)

        assertThat(proximo).isEqualTo(Instant.parse("2026-09-28T03:05:00Z"))
    }

    private fun occurrence(
        date: LocalDate,
        nextReminderAt: Instant?,
        snoozedUntil: Instant? = null,
        lastReminderAt: Instant? = null,
    ) = TaskOccurrence(
        id = OccurrenceIds.of("s1", date),
        seriesId = "s1",
        localDate = date,
        scheduledAt = date.atTime(8, 0).atZone(zone).toInstant(),
        status = OccurrenceStatus.PENDING,
        reminderStep = ReminderPolicy.STEP_HOURLY,
        nextReminderAt = nextReminderAt,
        lastReminderAt = lastReminderAt,
        snoozedUntil = snoozedUntil,
    )

    @Test
    fun skipDateGuardaADataEDescartaAsMuitoAntigas() {
        val antiga = LocalDate.of(2026, 1, 10)
        val recente = LocalDate.of(2026, 9, 20)
        val hoje = LocalDate.of(2026, 9, 27)
        val depois = OccurrenceLifecycle.skipDate(setOf(antiga, recente), hoje, hoje)
        assertThat(depois).containsExactly(recente, hoje)
    }

    /**
     * Teto de tombstones: as 240 datas do cenário estão todas dentro da janela de retenção, então
     * quem decide o que sobra é o teto — e o corte leva as mais distantes de hoje, nunca as
     * próximas (as que o preview e o `advance` vão materializar).
     */
    @Test
    fun skipDateTemTetoDeDatas() {
        val hoje = LocalDate.of(2026, 9, 27)
        val limite = hoje.minusDays(OccurrenceLifecycle.SKIPPED_RETENTION_DAYS)
        val muitas = (0 until 2 * OccurrenceLifecycle.MAX_SKIPPED_DATES)
            .map { limite.plusDays(it.toLong()) }
            .toSet()

        val depois = OccurrenceLifecycle.skipDate(muitas, hoje, hoje)

        assertThat(depois).hasSize(OccurrenceLifecycle.MAX_SKIPPED_DATES)
        assertThat(depois).contains(hoje)
        val descartadas = (muitas + hoje) - depois
        val maisDistanteGuardada = depois.maxOf { abs(ChronoUnit.DAYS.between(hoje, it)) }
        val maisProximaDescartada = descartadas.minOf { abs(ChronoUnit.DAYS.between(hoje, it)) }
        assertThat(maisProximaDescartada).isAtLeast(maisDistanteGuardada)
    }

    @Test
    fun skipDatePreservaAsDatasMaisProximasDeHoje() {
        val hoje = LocalDate.of(2026, 9, 27)
        val excluida = LocalDate.of(2026, 9, 28)
        val distantes = (0 until OccurrenceLifecycle.MAX_SKIPPED_DATES)
            .map { LocalDate.of(2027, 1, 1).plusDays(it.toLong()) }
            .toSet()

        val depois = OccurrenceLifecycle.skipDate(distantes, excluida, hoje)

        assertThat(depois).contains(excluida)
        assertThat(depois.size).isAtMost(OccurrenceLifecycle.MAX_SKIPPED_DATES)
    }

    @Test
    fun diaExcluidoNaoRenasceMesmoComTetoDeTombstonesCheio() {
        val hoje = LocalDate.of(2026, 9, 27)
        val excluida = LocalDate.of(2026, 9, 28)
        val distantes = (0 until OccurrenceLifecycle.MAX_SKIPPED_DATES)
            .map { LocalDate.of(2027, 1, 1).plusDays(it.toLong()) }
            .toSet()
        val skipped = OccurrenceLifecycle.skipDate(distantes, excluida, hoje)

        val change = OccurrenceLifecycle.advance(
            series = series(skipped = skipped),
            existing = emptyList(),
            now = now,
            todayInSeriesZone = excluida,
        )

        assertThat(change.upserts).isEmpty()
    }

    @Test
    fun unskipDateDevolveAData() {
        val hoje = LocalDate.of(2026, 9, 27)
        val depois = OccurrenceLifecycle.unskipDate(setOf(hoje, hoje.minusDays(1)), hoje)
        assertThat(depois).containsExactly(hoje.minusDays(1))
    }
}

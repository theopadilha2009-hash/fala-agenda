package com.theopadilha.falaagenda.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.QuietHours
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fim da escada de lembretes: a repetição não atravessa o dia local da ocorrência
 * (a única travessia é o adiamento do silêncio, que é o último degrau), e o teto de
 * passos fecha qualquer caminho que escape dessa regra.
 */
class ReminderLadderTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val quiet = QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0))

    private fun instant(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant()

    private fun passo(from: Instant, step: Int, occurrenceDay: LocalDate) =
        ReminderPolicy.nextRepetition(
            from = from,
            nextStep = ReminderPolicy.nextStep(step),
            zoneId = zone,
            quietHours = quiet,
            interval = ReminderPolicy.intervalAfterStep(step),
            occurrenceDay = occurrenceDay,
        )

    @Test
    fun sequenciaLongaDeRepeticoesNaoAtravessaAMeiaNoite() {
        val dia = LocalDate.of(2026, 8, 20)
        val disparos = mutableListOf<Instant>()
        var from = instant(2026, 8, 20, 20, 0)
        var step = ReminderPolicy.STEP_FIRST
        while (disparos.size < 50) {
            val plano = passo(from, step, dia)
            val fireAt = plano.fireAt ?: break
            disparos += fireAt
            from = fireAt
            step = plano.step
        }
        // Terminou por conta própria: a escada não fica tocando de hora em hora para sempre.
        assertThat(disparos).hasSize(4)
        assertThat(disparos).containsExactly(
            instant(2026, 8, 20, 20, 15),
            instant(2026, 8, 20, 20, 45),
            instant(2026, 8, 20, 21, 45),
            instant(2026, 8, 21, 8, 0),
        ).inOrder()
    }

    @Test
    fun ultimoDegrauDoDiaAdiadoPeloSilencioSobrevive() {
        val dia = LocalDate.of(2026, 8, 20)
        val adiado = passo(instant(2026, 8, 20, 21, 45), ReminderPolicy.STEP_HOURLY, dia)
        assertThat(adiado.fireAt).isEqualTo(instant(2026, 8, 21, 8, 0))
        assertThat(adiado.skippedQuietHours).isTrue()

        // Depois dele a escada acaba: o dia da ocorrência já ficou para trás.
        val depois = passo(requireNotNull(adiado.fireAt), adiado.step, dia)
        assertThat(depois.fireAt).isNull()
    }

    @Test
    fun ocorrenciaDeOntemNaoAgendaRepeticaoHoje() {
        val ontem = LocalDate.of(2026, 8, 20)
        val plano = passo(instant(2026, 8, 21, 9, 0), ReminderPolicy.STEP_HOURLY, ontem)
        assertThat(plano.fireAt).isNull()
    }

    @Test
    fun snoozeQueCaiDepoisDaMeiaNoiteContinuaAgendado() {
        val plano = ReminderPolicy.snooze(
            instant(2026, 8, 20, 23, 50),
            30,
            zone,
            quiet,
            respectQuietHours = false,
        )
        assertThat(plano.fireAt).isEqualTo(instant(2026, 8, 21, 0, 20))
    }

    @Test
    fun tetoDePassosEncerraAEscadaMesmoNoDiaDaOcorrencia() {
        val plano = ReminderPolicy.nextRepetition(
            from = instant(2026, 8, 20, 9, 0),
            nextStep = ReminderPolicy.MAX_STEP + 1,
            zoneId = zone,
            quietHours = quiet,
            interval = ReminderPolicy.intervalAfterStep(ReminderPolicy.STEP_HOURLY),
            occurrenceDay = LocalDate.of(2026, 8, 20),
        )
        assertThat(plano.fireAt).isNull()
    }

    @Test
    fun passosSeguemAvancandoDepoisDoHorario() {
        assertThat(ReminderPolicy.nextStep(ReminderPolicy.STEP_PLUS_30))
            .isEqualTo(ReminderPolicy.STEP_HOURLY)
        assertThat(ReminderPolicy.nextStep(ReminderPolicy.STEP_HOURLY))
            .isEqualTo(ReminderPolicy.STEP_HOURLY + 1)
        assertThat(ReminderPolicy.intervalAfterStep(ReminderPolicy.STEP_HOURLY))
            .isEqualTo(Duration.ofMinutes(60))
    }
}

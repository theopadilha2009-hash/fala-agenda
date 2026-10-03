package com.theopadilha.falaagenda.domain.reminder

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A primeira ocorrência de uma escolha manual: qual data nasce, e por que não é a escolhida.
 *
 * A regra que repete não pode nascer num instante já vencido. Um instante no passado entregue
 * ao `AlarmManager` dispara na hora: ela criava "tomar remédio, todo dia, às 18h" às 20:00
 * pelos chips e o celular apitava no ato do cadastro, seguindo a escada de repetições até o
 * fim do dia. A fala já fazia certo (`LocalTaskParser`: sem data explícita e com regra
 * recorrente, o horário vencido de hoje empurra para a próxima data da regra) — estas duas
 * contas agora são uma só.
 *
 * O cenário de [recorrenteComHoraVencidaAvancaParaAProximaData] é o mesmo que o
 * `PromessaDaTelaBateComOAgendamentoTest` roda do outro lado, contra o repositório de verdade:
 * é assim que a peça não volta a divergir do que é gravado.
 */
class DraftScheduleTest {
    private val zone = ZoneId.of("America/Sao_Paulo")

    /** Terça-feira, 29 de setembro de 2026. */
    private val terca = LocalDate.of(2026, 9, 29)

    private fun emHoras(hora: Int, minuto: Int = 0): Instant =
        terca.atTime(hora, minuto).atZone(zone).toInstant()

    private fun primeira(
        rule: RecurrenceRule,
        date: LocalDate = terca,
        time: LocalTime,
        now: Instant,
    ) = DraftSchedule.firstOccurrence(rule, date, time, zone, now)

    /**
     * O defeito relatado: às 20:00, "todo dia às 18h". A primeira ocorrência é amanhã, e não
     * hoje num instante que já passou.
     */
    @Test
    fun recorrenteComHoraVencidaAvancaParaAProximaData() {
        val primeira = primeira(
            rule = RecurrenceRule(RecurrenceKind.DAILY),
            time = LocalTime.of(18, 0),
            now = emHoras(20, 0),
        )

        assertThat(primeira.date).isEqualTo(LocalDate.of(2026, 9, 30))
        assertThat(primeira.movedBecause)
            .isEqualTo(DraftSchedule.FirstOccurrence.Reason.TIME_PASSED)
        // E o instante que sai daqui — o que vai para o banco e para o alarme — é o de amanhã.
        val instante = primeira.date.atTime(18, 0).atZone(zone).toInstant()
        assertThat(instante.atZone(zone).toLocalDateTime())
            .isEqualTo(LocalDateTime.of(2026, 9, 30, 18, 0))
        assertThat(instante).isAtLeast(emHoras(20, 0))
    }

    /** O outro lado: com o horário ainda de pé, nada muda — a escolha é a primeira ocorrência. */
    @Test
    fun recorrenteComHoraFuturaMantemADataEscolhida() {
        val primeira = primeira(
            rule = RecurrenceRule(RecurrenceKind.DAILY),
            time = LocalTime.of(21, 0),
            now = emHoras(20, 0),
        )

        assertThat(primeira.date).isEqualTo(terca)
        assertThat(primeira.movedBecause).isNull()
    }

    /**
     * A conta antiga, que continua valendo: a data escolhida é piso, quem decide é a regra. O
     * chip "Hoje" numa terça com "dias úteis" e uma data de sábado: o primeiro aviso é segunda.
     */
    @Test
    fun regraSemSabadoCaiNaProximaSegunda() {
        val sabado = LocalDate.of(2026, 10, 3)
        val primeira = primeira(
            rule = RecurrenceRule(RecurrenceKind.WEEKDAYS),
            date = sabado,
            time = LocalTime.of(9, 0),
            now = emHoras(15, 0),
        )

        assertThat(primeira.date).isEqualTo(LocalDate.of(2026, 10, 5))
        assertThat(primeira.movedBecause).isEqualTo(DraftSchedule.FirstOccurrence.Reason.RULE)
    }

    /**
     * A tarefa que não repete nasce vencida de propósito: a ocorrência é arquivada como não
     * realizada, sem alarme nenhum, e é isso que o `TaskRepository.saveDraft` decide com
     * [DraftSchedule.bornWithoutReminder]. Mover a data aqui apagaria o "não consegui avisar"
     * — o registro que a agenda sabe dar sobre o que já passou.
     */
    @Test
    fun naoRecorrenteMantemAEscolhaVencida() {
        val primeira = primeira(
            rule = RecurrenceRule(),
            time = LocalTime.of(8, 0),
            now = emHoras(15, 0),
        )

        assertThat(primeira.date).isEqualTo(terca)
        assertThat(primeira.movedBecause).isNull()
        val instante = primeira.date.atTime(8, 0).atZone(zone).toInstant()
        assertThat(instante).isLessThan(emHoras(15, 0))
        assertThat(DraftSchedule.bornWithoutReminder(instante, RecurrenceRule(), emHoras(15, 0)))
            .isTrue()
    }

    /**
     * A garantia que o defeito furava, para toda regra que repete: o instante da primeira
     * ocorrência nunca é anterior a agora — nem com o horário vencido hoje, nem com a data
     * escolhida já no passado (`LocalDate.of(2026, 8, 1)`, o "dia 1º todo mês" dito no fim de
     * setembro).
     */
    @Test
    fun nenhumaRegraRecorrenteNasceComInstanteVencido() {
        val regras = listOf(
            RecurrenceRule(RecurrenceKind.DAILY),
            RecurrenceRule(RecurrenceKind.WEEKDAYS),
            RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = setOf(DayOfWeek.MONDAY)),
            RecurrenceRule(RecurrenceKind.WEEKLY, weekDays = setOf(DayOfWeek.TUESDAY)),
            RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = 1),
            RecurrenceRule(RecurrenceKind.MONTHLY, dayOfMonth = 29),
            RecurrenceRule(RecurrenceKind.YEARLY, dayOfMonth = 1, monthOfYear = 9),
            RecurrenceRule(RecurrenceKind.YEARLY, dayOfMonth = 29, monthOfYear = 9),
        )
        val datas = listOf(terca, terca.minusDays(1), LocalDate.of(2026, 8, 1))
        val horas = listOf(LocalTime.of(0, 1), LocalTime.of(8, 0), LocalTime.of(18, 0), LocalTime.of(23, 59))

        regras.forEach { rule ->
            datas.forEach { data ->
                horas.forEach { hora ->
                    val primeira = primeira(rule, data, hora, emHoras(20, 0))
                    val instante = primeira.date.atTime(hora).atZone(zone).toInstant()
                    assertThat(instante)
                        .isAtLeast(emHoras(20, 0))
                    assertThat(primeira.date).isAtLeast(terca)
                }
            }
        }
    }
}

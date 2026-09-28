package com.theopadilha.falaagenda.data.repo

import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.local.toDomain
import com.theopadilha.falaagenda.data.local.toEntity
import com.theopadilha.falaagenda.domain.model.OccurrenceIds
import com.theopadilha.falaagenda.domain.model.OccurrenceStatus
import com.theopadilha.falaagenda.domain.model.ParsedTaskDraft
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.reminder.ReminderPolicy
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TaskRepositoryTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val clock = FixedAppClock(
        LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant(),
        zone,
    )
    private val seriesDao = FakeSeriesDao()
    private val occurrenceDao = FakeOccurrenceDao()
    private val scheduler = RecordingScheduler()
    private val repo = TaskRepository(seriesDao, occurrenceDao, clock, scheduler)

    @Test
    fun saveAndCompleteUniqueTask() = runBlocking {
        val draft = completeDraft("Tomar remédio", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0))
        val saved = repo.saveDraft(draft)
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
        assertThat(scheduler.scheduled).hasSize(1)
        repo.complete(saved.occurrence.id)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(row.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
        assertThat(scheduler.cancelled).contains(saved.occurrence.id)
    }

    @Test
    fun saveDraftGuardaValor() = runBlocking {
        val draft = completeDraft("Cabelo", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0))
            .copy(amountCents = 8000)
        val saved = repo.saveDraft(draft)
        assertThat(saved.series.amountCents).isEqualTo(8000)
        assertThat(seriesDao.get(saved.series.id)?.amountCents).isEqualTo(8000)
    }

    @Test
    fun saveDraftGuardaObservacao() = runBlocking {
        val draft = completeDraft("Cabelo", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0))
            .copy(observation = "levar a carteirinha")
        val saved = repo.saveDraft(draft)
        assertThat(saved.series.observation).isEqualTo("levar a carteirinha")
        assertThat(seriesDao.get(saved.series.id)?.observation).isEqualTo("levar a carteirinha")
    }

    @Test
    fun editarConcluidaSoValorNaoDesfaz() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Cabelo", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)))
        repo.complete(saved.occurrence.id)
        assertThat(occurrenceDao.get(saved.occurrence.id)!!.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
        repo.editOccurrence(
            saved.occurrence.id,
            "Cabelo",
            LocalDate.of(2026, 8, 21),
            LocalTime.of(9, 0),
            RecurrenceRule(),
            8000,
        )
        val row = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(row.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
        assertThat(seriesDao.get(saved.series.id)!!.amountCents).isEqualTo(8000)
        assertThat(scheduler.scheduled.count { it == saved.occurrence.id }).isEqualTo(1)
    }

    @Test
    fun pastUniqueBecomesMissed() = runBlocking {
        val draft = completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0))
        val saved = repo.saveDraft(draft)
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test
    fun editCancelsAndReschedules() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(14, 0)))
        repo.editOccurrence(
            saved.occurrence.id,
            "Consulta médica",
            LocalDate.of(2026, 8, 23),
            LocalTime.of(15, 0),
            RecurrenceRule(),
        )
        assertThat(scheduler.cancelled).contains(saved.occurrence.id)
        assertThat(scheduler.scheduled.size).isAtLeast(2)
    }

    @Test
    fun deniedExactAlarmStillSaves() = runBlocking {
        scheduler.exact = false
        val saved = repo.saveDraft(completeDraft("Farmácia", LocalDate.of(2026, 8, 21), LocalTime.of(11, 0)))
        assertThat(saved.usedInexactAlarm).isTrue()
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
    }

    @Test
    fun rebootReschedulesPending() = runBlocking {
        repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        scheduler.scheduled.clear()
        repo.rescheduleAll()
        assertThat(scheduler.scheduled).isNotEmpty()
    }

    @Test
    fun duasTarefasNoMesmoHorarioNaoColidem() = runBlocking<Unit> {
        val a = repo.saveDraft(completeDraft("A", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)))
        val b = repo.saveDraft(completeDraft("B", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)))
        assertThat(a.occurrence.id).isNotEqualTo(b.occurrence.id)
        assertThat(scheduler.scheduled).containsExactly(a.occurrence.id, b.occurrence.id)
    }

    @Test
    fun reschedulePreservaSnooze() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.snooze(saved.occurrence.id, 30)
        val afterSnooze = occurrenceDao.get(saved.occurrence.id)!!
        scheduler.scheduled.clear()
        repo.rescheduleAll()
        val again = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(again.nextReminderAtEpochMs).isEqualTo(afterSnooze.nextReminderAtEpochMs)
        assertThat(again.reminderStep).isEqualTo(afterSnooze.reminderStep)
        assertThat(scheduler.scheduled).contains(saved.occurrence.id)
    }

    @Test
    fun alarmeDeOcorrenciaConcluidaNaoNotifica() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.complete(saved.occurrence.id)
        val result = repo.onAlarmFired(saved.occurrence.id)
        assertThat(result.notify).isFalse()
    }

    @Test
    fun editParaHorarioPassadoViraNaoRealizada() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(14, 0)))
        repo.editOccurrence(
            saved.occurrence.id,
            "Consulta",
            LocalDate.of(2026, 8, 19),
            LocalTime.of(9, 0),
            RecurrenceRule(),
        )
        val row = occurrenceDao.getAll().single()
        assertThat(row.status).isEqualTo(OccurrenceStatus.MISSED.name)
        assertThat(row.nextReminderAtEpochMs).isNull()
    }

    @Test
    fun deleteCancelaAlarme() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0)))
        repo.deleteOccurrence(saved.occurrence.id)
        assertThat(scheduler.cancelled).contains(saved.occurrence.id)
        assertThat(occurrenceDao.get(saved.occurrence.id)).isNull()
    }

    @Test
    fun desfazerExclusaoReagenda() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Farmácia", LocalDate.of(2026, 8, 22), LocalTime.of(11, 0)))
        val item = AgendaItem(saved.occurrence, saved.series)
        repo.deleteOccurrence(saved.occurrence.id)
        scheduler.scheduled.clear()
        repo.restore(item)
        assertThat(occurrenceDao.get(saved.occurrence.id)).isNotNull()
        assertThat(occurrenceDao.get(saved.occurrence.id)!!.status).isEqualTo(OccurrenceStatus.PENDING.name)
        assertThat(scheduler.scheduled).contains(saved.occurrence.id)
    }

    @Test
    fun completeNaoMexemJaConcluida() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.complete(saved.occurrence.id)
        val first = occurrenceDao.get(saved.occurrence.id)!!
        repo.complete(saved.occurrence.id)
        val second = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(second.completedAtEpochMs).isEqualTo(first.completedAtEpochMs)
        assertThat(second.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
    }

    @Test
    fun completeTambemFechaNaoRealizada() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0)))
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        repo.complete(saved.occurrence.id)
        assertThat(occurrenceDao.get(saved.occurrence.id)!!.status).isEqualTo(OccurrenceStatus.COMPLETED.name)
    }

    @Test
    fun retryMissedComHorarioAbertoFicaHoje() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(14, 0)))
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        scheduler.scheduled.clear()
        val result = repo.retryMissed(saved.occurrence.id)
        assertThat(result).isNotNull()
        assertThat(result!!.date).isEqualTo(LocalDate.of(2026, 8, 20))
        assertThat(result.time).isEqualTo(LocalTime.of(14, 0))
        assertThat(occurrenceDao.get(saved.occurrence.id)).isNull()
        val fresh = occurrenceDao.getAll().single()
        assertThat(fresh.status).isEqualTo(OccurrenceStatus.PENDING.name)
        assertThat(fresh.localDate).isEqualTo("2026-08-20")
        assertThat(scheduler.scheduled).contains(fresh.id)
    }

    @Test
    fun retryMissedComHorarioPassadoVaiAmanha() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Manhã", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0)))
        val result = repo.retryMissed(saved.occurrence.id)
        assertThat(result!!.date).isEqualTo(LocalDate.of(2026, 8, 21))
        val fresh = occurrenceDao.getAll().single()
        assertThat(fresh.localDate).isEqualTo("2026-08-21")
        assertThat(fresh.status).isEqualTo(OccurrenceStatus.PENDING.name)
    }

    @Test
    fun retryMissedIgnoraRecorrente() = runBlocking {
        val draft = completeDraft("Remédio", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0))
            .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
        val saved = repo.saveDraft(draft)
        val pending = occurrenceDao.getAll().single()
        occurrenceDao.upsert(pending.copy(status = OccurrenceStatus.MISSED.name, missedAtEpochMs = clock.instant().toEpochMilli()))
        val result = repo.retryMissed(pending.id)
        assertThat(result).isNull()
        assertThat(occurrenceDao.get(pending.id)!!.status).isEqualTo(OccurrenceStatus.MISSED.name)
        assertThat(saved.series.recurrence.kind).isEqualTo(RecurrenceKind.DAILY)
    }

    @Test
    fun retryMissedIgnoraPendente() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0)))
        assertThat(repo.retryMissed(saved.occurrence.id)).isNull()
        assertThat(occurrenceDao.get(saved.occurrence.id)!!.status).isEqualTo(OccurrenceStatus.PENDING.name)
    }

    @Test
    fun snoozeDezMinutos() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))
        repo.snooze(saved.occurrence.id, 10)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        val expected = clock.instant().plusSeconds(10 * 60).toEpochMilli()
        assertThat(row.snoozedUntilEpochMs).isEqualTo(expected)
        assertThat(row.nextReminderAtEpochMs).isEqualTo(expected)
    }

    @Test
    fun desfazerConcluirFuturaReagenda() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Consulta", LocalDate.of(2026, 8, 22), LocalTime.of(10, 0)))
        val item = AgendaItem(saved.occurrence, saved.series)
        repo.complete(saved.occurrence.id)
        scheduler.scheduled.clear()
        repo.uncomplete(item)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(row.status).isEqualTo(OccurrenceStatus.PENDING.name)
        assertThat(scheduler.scheduled).contains(saved.occurrence.id)
    }

    @Test
    fun desfazerConcluirNaoRealizadaVoltaAtrasada() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Já passou", LocalDate.of(2026, 8, 19), LocalTime.of(9, 0)))
        assertThat(saved.occurrence.status).isEqualTo(OccurrenceStatus.MISSED)
        val item = AgendaItem(saved.occurrence, saved.series)
        repo.complete(saved.occurrence.id)
        scheduler.scheduled.clear()
        repo.uncomplete(item)
        val row = occurrenceDao.get(saved.occurrence.id)!!
        assertThat(row.status).isEqualTo(OccurrenceStatus.MISSED.name)
        assertThat(scheduler.scheduled).isEmpty()
    }

    /**
     * Excluir uma data de uma série diária não pode deixá-la voltar no próximo start:
     * era por aqui que o tombstone morria, porque o preview começa justamente em hoje.
     */
    @Test
    fun excluirOcorrenciaDeSerieDiariaNaoVoltaAoReagendar() {
        runBlocking {
            val draft = completeDraft("Tomar remédio", LocalDate.of(2026, 8, 20), LocalTime.of(8, 0))
                .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
            val saved = repo.saveDraft(draft)
            val inicio = saved.occurrence.id

            repo.deleteOccurrence(inicio)
            assertThat(occurrenceDao.get(inicio)).isNull()
            assertThat(seriesDao.get(saved.series.id)!!.toDomain().skippedDates)
                .containsExactly(LocalDate.of(2026, 8, 20))

            repo.rescheduleAll()

            assertThat(occurrenceDao.get(inicio)).isNull()
            assertThat(occurrenceDao.forSeries(saved.series.id).map { it.localDate })
                .doesNotContain("2026-08-20")
        }
    }

    /** "Excluir" é uma data; "Encerrar série" é a série inteira. */
    @Test
    fun excluirUltimaOcorrenciaDeSerieRecorrenteMantemASerie() {
        runBlocking {
            val draft = completeDraft("Tomar remédio", LocalDate.of(2026, 8, 20), LocalTime.of(8, 0))
                .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
            val saved = repo.saveDraft(draft)

            repo.deleteOccurrence(saved.occurrence.id)

            assertThat(seriesDao.get(saved.series.id)).isNotNull()
            assertThat(repo.snapshotAgenda().today).isEmpty()
        }
    }

    /** Desfazer o "Excluir" tem que tirar o tombstone junto. */
    @Test
    fun desfazerExclusaoLimpaOTombstone() {
        runBlocking {
            val draft = completeDraft("Tomar remédio", LocalDate.of(2026, 8, 20), LocalTime.of(8, 0))
                .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
            val saved = repo.saveDraft(draft)
            val item = repo.snapshotAgenda().find(saved.occurrence.id)!!

            repo.deleteOccurrence(saved.occurrence.id)
            repo.restore(item)

            assertThat(seriesDao.get(saved.series.id)!!.toDomain().skippedDates).isEmpty()
            assertThat(occurrenceDao.get(saved.occurrence.id)).isNotNull()
        }
    }

    /** Excluir tarefa única continua apagando a série junto (não deixa linha órfã). */
    @Test
    fun excluirTarefaUnicaApagaASerie() {
        runBlocking {
            val saved = repo.saveDraft(
                completeDraft("Dentista", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)),
            )

            repo.deleteOccurrence(saved.occurrence.id)

            assertThat(seriesDao.get(saved.series.id)).isNull()
        }
    }

    /**
     * O cruzamento que a tela alcança e que ninguém testava: tarefa única, ela conclui, edita
     * para outro dia e exclui o cartão novo. A COMPLETED sobrevive na série (é o registro do
     * que ela fez), então o "Excluir" não cai no ramo "não sobrou nada" — e o ramo da tarefa
     * única apagava a linha sem tombstone. No próximo start o `advance` rematerializava
     * `startLocalDate` como PENDENTE e rearmava o alarme: o que ela excluiu voltava e tocava.
     */
    @Test
    fun excluirTarefaUnicaComHistoricoNaoVoltaAoReagendar() {
        runBlocking {
            val saved = repo.saveDraft(
                completeDraft("Dentista", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)),
            )
            repo.complete(saved.occurrence.id)
            repo.editOccurrence(
                saved.occurrence.id,
                "Dentista",
                LocalDate.of(2026, 8, 22),
                LocalTime.of(9, 0),
                RecurrenceRule(),
            )
            val amanha = OccurrenceIds.of(saved.series.id, LocalDate.of(2026, 8, 22))
            assertThat(occurrenceDao.get(amanha)).isNotNull()

            repo.deleteOccurrence(amanha)
            assertThat(occurrenceDao.get(amanha)).isNull()
            scheduler.scheduled.clear()

            repo.rescheduleAll()

            assertThat(occurrenceDao.get(amanha)).isNull()
            assertThat(scheduler.scheduled).doesNotContain(amanha)
            // O que ela fez continua na agenda: o histórico não vai junto com o "Excluir".
            assertThat(repo.snapshotAgenda().completed.map { it.occurrence.localDate })
                .containsExactly(LocalDate.of(2026, 8, 21))
        }
    }

    /**
     * O horário ela lê na tela; quem dispara é o fuso. A série fica gravada com o fuso do dia
     * do cadastro, e trocando o fuso do celular o aviso passava a tocar 08:00 do fuso velho —
     * deslocado, calado e para sempre em toda série já criada. Aqui é uma pessoa, um celular,
     * um app: o 08:00 que ela lê é o 08:00 daqui.
     */
    @Test
    fun disparoUsaOFusoDoRelogioAgoraENaoOFusoGravadoNaSerie() {
        runBlocking {
            val lisboa = ZoneId.of("Europe/Lisbon")
            // 06:00 em Lisboa (o relógio mudou de fuso); a série tinha sido cadastrada em SP.
            val relogio = FixedAppClock(Instant.parse("2026-09-27T05:00:00Z"), lisboa)
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoDeLisboa = TaskRepository(seriesDao, occDao, relogio, sched)
            val hoje = LocalDate.of(2026, 9, 27)
            val criadaEmSaoPaulo = serieDe("s-fuso", "Remédio").copy(startLocalDate = hoje)
            seriesDao.upsert(criadaEmSaoPaulo.toEntity())
            val id = OccurrenceIds.of(criadaEmSaoPaulo.id, hoje)
            occDao.upsert(
                ocorrenciaDe(
                    criadaEmSaoPaulo.id,
                    hoje,
                    LocalTime.of(8, 0),
                    OccurrenceStatus.PENDING,
                ).toEntity(),
            )

            repoDeLisboa.rescheduleAll()

            val esperado = hoje.atTime(8, 0).atZone(lisboa).toInstant()
            val depois = occDao.get(id)!!.toDomain()
            assertThat(depois.status).isEqualTo(OccurrenceStatus.PENDING)
            assertThat(depois.scheduledAt).isEqualTo(esperado)
            assertThat(depois.nextReminderAt).isEqualTo(esperado)
            assertThat(sched.scheduled).contains(id)
        }
    }

    /**
     * A pendente que atravessou a meia-noite (aviso adiado pela noite, ainda tocando)
     * continua acionável: ela entra em "Hoje", que para esta usuária é "o que eu preciso
     * fazer agora", e vem antes das de hoje porque é a mais urgente. Antes ela não caía
     * em nenhuma das quatro seções — invisível no app.
     */
    @Test
    fun pendenteAtrasadaApareceEmHojeAntesDasDeHoje() {
        runBlocking {
            val remedio = serieDe("s-rem", "Remédio")
            val agua = serieDe("s-agua", "Água")
            listOf(remedio, agua).forEach { seriesDao.upsert(it.toEntity()) }
            // Duas atrasadas e duas de hoje, em séries diferentes: a ordem esperada prova
            // que as atrasadas vêm primeiro e que cada grupo sai por scheduledAt.
            val atrasada = ocorrenciaDe(remedio.id, LocalDate.of(2026, 8, 18), LocalTime.of(20, 0), OccurrenceStatus.PENDING)
            val atrasadaOutra = ocorrenciaDe(agua.id, LocalDate.of(2026, 8, 19), LocalTime.of(8, 0), OccurrenceStatus.PENDING)
            val cedo = ocorrenciaDe(remedio.id, LocalDate.of(2026, 8, 20), LocalTime.of(7, 0), OccurrenceStatus.PENDING)
            val tarde = ocorrenciaDe(agua.id, LocalDate.of(2026, 8, 20), LocalTime.of(21, 0), OccurrenceStatus.PENDING)
            listOf(atrasada, atrasadaOutra, cedo, tarde).forEach { occurrenceDao.upsert(it.toEntity()) }

            val sections = repo.snapshotAgenda()

            assertThat(sections.today.map { it.occurrence.id })
                .containsExactly(atrasada.id, atrasadaOutra.id, cedo.id, tarde.id)
                .inOrder()
            // Cada ocorrência em exatamente uma seção: nada de item duplicado.
            assertThat(
                (sections.today + sections.upcoming + sections.completed + sections.missed)
                    .map { it.occurrence.id },
            ).containsExactly(atrasada.id, atrasadaOutra.id, cedo.id, tarde.id)
        }
    }

    /** O toque na notificação resolve a ocorrência por `find`: a atrasada pendente tem que estar lá. */
    @Test
    fun findAchaPendenteAtrasada() {
        runBlocking {
            val series = serieDe("s-rem", "Remédio")
            seriesDao.upsert(series.toEntity())
            val atrasada = ocorrenciaDe(series.id, LocalDate.of(2026, 8, 19), LocalTime.of(8, 0), OccurrenceStatus.PENDING)
            occurrenceDao.upsert(atrasada.toEntity())

            val found = repo.snapshotAgenda().find(atrasada.id)

            assertThat(found).isNotNull()
            assertThat(found!!.occurrence.status).isEqualTo(OccurrenceStatus.PENDING)
            assertThat(found.series.title).isEqualTo("Remédio")
        }
    }

    /** A não realizada não é acionável: continua só em "não realizadas", fora de "Hoje". */
    @Test
    fun naoRealizadaAtrasadaFicaSoEmNaoRealizadas() {
        runBlocking {
            val series = serieDe("s-rem", "Remédio")
            seriesDao.upsert(series.toEntity())
            val vencida = ocorrenciaDe(
                series.id,
                LocalDate.of(2026, 8, 19),
                LocalTime.of(8, 0),
                OccurrenceStatus.MISSED,
                missedAt = clock.instant(),
            )
            val deHoje = ocorrenciaDe(series.id, LocalDate.of(2026, 8, 20), LocalTime.of(8, 0), OccurrenceStatus.PENDING)
            listOf(vencida, deHoje).forEach { occurrenceDao.upsert(it.toEntity()) }

            val sections = repo.snapshotAgenda()

            assertThat(sections.today.map { it.occurrence.id }).containsExactly(deHoje.id)
            assertThat(sections.missed.map { it.occurrence.id }).containsExactly(vencida.id)
        }
    }

    /** Pendente de amanhã continua só em "Próximas". */
    @Test
    fun pendenteDeAmanhaContinuaSoEmProximas() {
        runBlocking {
            val series = serieDe("s-rem", "Remédio")
            seriesDao.upsert(series.toEntity())
            val amanha = ocorrenciaDe(series.id, LocalDate.of(2026, 8, 21), LocalTime.of(8, 0), OccurrenceStatus.PENDING)
            occurrenceDao.upsert(amanha.toEntity())

            val sections = repo.snapshotAgenda()

            assertThat(sections.today).isEmpty()
            assertThat(sections.upcoming.map { it.occurrence.id }).containsExactly(amanha.id)
        }
    }

    private fun serieDe(id: String, title: String) = TaskSeries(
        id = id,
        title = title,
        zoneId = zone,
        localTime = LocalTime.of(8, 0),
        startLocalDate = LocalDate.of(2026, 8, 18),
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        createdAt = clock.instant(),
        updatedAt = clock.instant(),
    )

    private fun ocorrenciaDe(
        seriesId: String,
        dia: LocalDate,
        hora: LocalTime,
        status: OccurrenceStatus,
        missedAt: Instant? = null,
    ) = TaskOccurrence(
        id = OccurrenceIds.of(seriesId, dia),
        seriesId = seriesId,
        localDate = dia,
        scheduledAt = dia.atTime(hora).atZone(zone).toInstant(),
        status = status,
        missedAt = missedAt,
    )

    /**
     * O lembrete adiado pelo horário de silêncio só toca às 08:00 do dia seguinte — quando
     * a ocorrência já é de ontem. O disparo que chega alguns segundos depois da hora marcada
     * não pode ser engolido pela varredura de ciclo de vida: é o último instante em que
     * aquele aviso tem para tocar, e é o aviso de HOJE.
     *
     * O último degrau já tocou, então a escada termina aqui (não há repetição nova), mas a
     * ocorrência segue pendente até a virada do dia — é o que faz o "Adiar" e o "Concluir"
     * da própria notificação funcionarem.
     */
    @Test
    fun lembreteAdiadoPelaNoiteTocaNoFimDoSilencio() {
        runBlocking {
            val oitoDaManha = FixedAppClock(
                LocalDateTime.of(2026, 8, 20, 8, 0).atZone(zone).toInstant(),
                zone,
            )
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoDaManha = TaskRepository(seriesDao, occDao, oitoDaManha, sched)
            val series = serieDaNoite(oitoDaManha.instant())
            seriesDao.upsert(series.toEntity())
            val ontem = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 19))
            occDao.upsert(
                ocorrenciaAdiada(
                    seriesId = series.id,
                    dia = LocalDate.of(2026, 8, 19),
                    lastReminderAt = LocalDateTime.of(2026, 8, 19, 23, 0).atZone(zone).toInstant(),
                    // marcado para 07:59, o sistema entregou às 08:00
                    nextReminderAt = LocalDateTime.of(2026, 8, 20, 7, 59).atZone(zone).toInstant(),
                ).toEntity(),
            )

            val result = repoDaManha.onAlarmFired(ontem)

            assertThat(result.notify).isTrue()
            val stored = occDao.get(ontem)!!.toDomain()
            assertThat(stored.status).isEqualTo(OccurrenceStatus.PENDING)
            // O último degrau do dia já tocou: a escada não atravessa para o dia seguinte.
            assertThat(stored.nextReminderAt).isNull()
            assertThat(sched.cancelled).doesNotContain(ontem)

            // O "Adiar" da notificação só age em ocorrência pendente.
            sched.scheduled.clear()
            repoDaManha.snooze(ontem, 30)

            val adiada = occDao.get(ontem)!!.toDomain()
            val esperado = oitoDaManha.instant().plusSeconds(30 * 60)
            assertThat(adiada.status).isEqualTo(OccurrenceStatus.PENDING)
            assertThat(adiada.snoozedUntil).isEqualTo(esperado)
            assertThat(adiada.nextReminderAt).isEqualTo(esperado)
            assertThat(sched.scheduled).contains(ontem)
        }
    }

    /**
     * O terminador é o dia do último aviso entregue: a ocorrência de 19/08 cujo aviso tocou
     * em 20/08 vira não realizada na virada para 21/08, não às 08:00 do dia 20.
     */
    @Test
    fun ocorrenciaAdiadaViraNaoRealizadaNaViradaDoDia() {
        runBlocking {
            val diaSeguinte = FixedAppClock(
                LocalDateTime.of(2026, 8, 21, 9, 0).atZone(zone).toInstant(),
                zone,
            )
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoDoDiaSeguinte = TaskRepository(seriesDao, occDao, diaSeguinte, sched)
            val series = serieDaNoite(diaSeguinte.instant())
            seriesDao.upsert(series.toEntity())
            val ontem = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 19))
            occDao.upsert(
                ocorrenciaAdiada(
                    seriesId = series.id,
                    dia = LocalDate.of(2026, 8, 19),
                    lastReminderAt = LocalDateTime.of(2026, 8, 20, 8, 0).atZone(zone).toInstant(),
                    nextReminderAt = null,
                ).toEntity(),
            )

            repoDoDiaSeguinte.rescheduleAll()

            val stored = occDao.get(ontem)!!.toDomain()
            assertThat(stored.status).isEqualTo(OccurrenceStatus.MISSED)
            assertThat(stored.nextReminderAt).isNull()
            assertThat(sched.cancelled).contains(ontem)
        }
    }

    /** Data vencida que nunca teve aviso entregue continua virando não realizada de imediato. */
    @Test
    fun ocorrenciaVencidaSemNuncaTerTidoLembreteViraNaoRealizada() {
        runBlocking {
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoHoje = TaskRepository(seriesDao, occDao, clock, sched)
            val series = serieDaNoite(clock.instant())
            seriesDao.upsert(series.toEntity())
            val vencida = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 17))
            occDao.upsert(
                ocorrenciaAdiada(
                    seriesId = series.id,
                    dia = LocalDate.of(2026, 8, 17),
                    lastReminderAt = null,
                    nextReminderAt = null,
                ).toEntity(),
            )

            repoHoje.rescheduleAll()

            assertThat(occDao.get(vencida)!!.status).isEqualTo(OccurrenceStatus.MISSED.name)
            assertThat(sched.cancelled).contains(vencida)
        }
    }

    private fun serieDaNoite(now: Instant) = TaskSeries(
        id = "s1",
        title = "Remédio",
        zoneId = zone,
        localTime = LocalTime.of(22, 0),
        startLocalDate = LocalDate.of(2026, 8, 19),
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        createdAt = now,
        updatedAt = now,
    )

    private fun ocorrenciaAdiada(
        seriesId: String,
        dia: LocalDate,
        lastReminderAt: Instant?,
        nextReminderAt: Instant?,
    ) = TaskOccurrence(
        id = OccurrenceIds.of(seriesId, dia),
        seriesId = seriesId,
        localDate = dia,
        scheduledAt = dia.atTime(22, 0).atZone(zone).toInstant(),
        status = OccurrenceStatus.PENDING,
        reminderStep = ReminderPolicy.STEP_HOURLY,
        nextReminderAt = nextReminderAt,
        lastReminderAt = lastReminderAt,
    )

    /** Sem lembrete vivo, a data vencida continua virando não realizada. */
    @Test
    fun lembreteDeOntemSemNadaMarcadoViraNaoRealizada() {
        runBlocking {
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoAgora = TaskRepository(seriesDao, occDao, clock, sched)
            val series = TaskSeries(
                id = "s1",
                title = "Remédio",
                zoneId = zone,
                localTime = LocalTime.of(8, 0),
                startLocalDate = LocalDate.of(2026, 8, 19),
                recurrence = RecurrenceRule(RecurrenceKind.DAILY),
                createdAt = clock.instant(),
                updatedAt = clock.instant(),
            )
            seriesDao.upsert(series.toEntity())
            val ontem = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 19))
            occDao.upsert(
                TaskOccurrence(
                    id = ontem,
                    seriesId = series.id,
                    localDate = LocalDate.of(2026, 8, 19),
                    scheduledAt = LocalDateTime.of(2026, 8, 19, 8, 0).atZone(zone).toInstant(),
                    status = OccurrenceStatus.PENDING,
                    reminderStep = ReminderPolicy.STEP_PLUS_30,
                    nextReminderAt = LocalDateTime.of(2026, 8, 19, 9, 30).atZone(zone).toInstant(),
                ).toEntity(),
            )

            repoAgora.rescheduleAll()

            assertThat(occDao.get(ontem)!!.status).isEqualTo(OccurrenceStatus.MISSED.name)
        }
    }

    /**
     * O alarme marcado para as 08:00 só é entregue às 08:00 e pouco (Doze, entrega
     * inexata, processo acordando naquele instante). A varredura de start que lê o banco
     * 100 ms depois do horário marcado não pode arquivar como "não realizada" o aviso que
     * ainda vai tocar: era o único lembrete do dia morrendo sem tocar.
     */
    @Test
    fun varreduraNaoConsomeLembreteAtrasadoENaoEntregue() {
        runBlocking {
            val oitoDaManha = FixedAppClock(
                LocalDateTime.of(2026, 8, 20, 8, 0, 0, 100_000_000).atZone(zone).toInstant(),
                zone,
            )
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoDaManha = TaskRepository(seriesDao, occDao, oitoDaManha, sched)
            val series = serieDaNoite(oitoDaManha.instant())
            seriesDao.upsert(series.toEntity())
            val ontem = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 19))
            occDao.upsert(
                ocorrenciaAdiada(
                    seriesId = series.id,
                    dia = LocalDate.of(2026, 8, 19),
                    lastReminderAt = LocalDateTime.of(2026, 8, 19, 22, 15).atZone(zone).toInstant(),
                    // escada terminou em 08:00 de hoje; o alarme ainda não voltou
                    nextReminderAt = LocalDateTime.of(2026, 8, 20, 8, 0).atZone(zone).toInstant(),
                ).toEntity(),
            )

            repoDaManha.rescheduleAll()

            val stored = occDao.get(ontem)!!.toDomain()
            assertThat(stored.status).isEqualTo(OccurrenceStatus.PENDING)
            assertThat(sched.cancelled).doesNotContain(ontem)
            // Rearmado: é o que faz a entrega atrasada ainda acontecer.
            assertThat(sched.scheduled).contains(ontem)

            // O "Adiar" da notificação só age em ocorrência pendente.
            sched.scheduled.clear()
            repoDaManha.snooze(ontem, 30)

            val adiada = occDao.get(ontem)!!.toDomain()
            assertThat(adiada.status).isEqualTo(OccurrenceStatus.PENDING)
            assertThat(adiada.snoozedUntil).isEqualTo(oitoDaManha.instant().plusSeconds(30 * 60))
            assertThat(adiada.nextReminderAt).isEqualTo(adiada.snoozedUntil)
            assertThat(sched.scheduled).contains(ontem)
        }
    }

    /**
     * Terminador da entrega pendente: passada a janela, o alarme perdido não fica pendurado
     * para sempre — a ocorrência volta a ser encerrada pela virada do dia.
     */
    @Test
    fun lembreteNaoEntregueForaDaJanelaViraNaoRealizada() {
        runBlocking {
            val manhaSeguinte = FixedAppClock(
                LocalDateTime.of(2026, 8, 20, 9, 0).atZone(zone).toInstant(),
                zone,
            )
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoDaManha = TaskRepository(seriesDao, occDao, manhaSeguinte, sched)
            val series = serieDaNoite(manhaSeguinte.instant())
            seriesDao.upsert(series.toEntity())
            val ontem = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 19))
            occDao.upsert(
                ocorrenciaAdiada(
                    seriesId = series.id,
                    dia = LocalDate.of(2026, 8, 19),
                    lastReminderAt = LocalDateTime.of(2026, 8, 19, 21, 30).atZone(zone).toInstant(),
                    // marcado para ontem 22:00, nunca entregue: 11 h de atraso, janela fechada
                    nextReminderAt = LocalDateTime.of(2026, 8, 19, 22, 0).atZone(zone).toInstant(),
                ).toEntity(),
            )

            repoDaManha.rescheduleAll()

            val stored = occDao.get(ontem)!!.toDomain()
            assertThat(stored.status).isEqualTo(OccurrenceStatus.MISSED)
            assertThat(stored.nextReminderAt).isNull()
            assertThat(sched.cancelled).contains(ontem)
        }
    }

    /**
     * A outra metade da entrega pendente: o aviso que ainda NÃO tocou. O remédio das 22:00
     * não é entregue (Doze, alarme inexato, aparelho desligado), a escada nem começou
     * (`lastReminderAt` nulo) e ela só liga o aparelho às 00:30. A varredura da virada da
     * meia-noite arquivava a ocorrência como não realizada e cancelava o alarme: o único
     * aviso do dia morria calado. Dentro da janela ele é entrega pendente como qualquer
     * outro, e o `rescheduleAll` rearma o primeiro degrau.
     */
    @Test
    fun avisoNuncaEntregueNaViradaDaMeiaNoiteContinuaPendente() {
        runBlocking {
            val madrugada = FixedAppClock(
                LocalDateTime.of(2026, 8, 20, 0, 30).atZone(zone).toInstant(),
                zone,
            )
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoDaMadrugada = TaskRepository(seriesDao, occDao, madrugada, sched)
            val series = serieDaNoite(madrugada.instant())
            seriesDao.upsert(series.toEntity())
            val ontem = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 19))
            occDao.upsert(
                ocorrenciaAdiada(
                    seriesId = series.id,
                    dia = LocalDate.of(2026, 8, 19),
                    lastReminderAt = null,
                    nextReminderAt = LocalDateTime.of(2026, 8, 19, 22, 0).atZone(zone).toInstant(),
                ).copy(reminderStep = ReminderPolicy.STEP_FIRST).toEntity(),
            )

            repoDaMadrugada.rescheduleAll()

            val stored = occDao.get(ontem)!!.toDomain()
            assertThat(stored.status).isEqualTo(OccurrenceStatus.PENDING)
            assertThat(sched.cancelled).doesNotContain(ontem)
            // Rearmado no primeiro degrau: é o que faz o aviso atrasado ainda tocar.
            assertThat(sched.scheduled).contains(ontem)
        }
    }

    /** O terminador da janela vale também para o aviso que nunca tocou. */
    @Test
    fun avisoNuncaEntregueForaDaJanelaViraNaoRealizada() {
        runBlocking {
            val manhaSeguinte = FixedAppClock(
                LocalDateTime.of(2026, 8, 20, 9, 0).atZone(zone).toInstant(),
                zone,
            )
            val occDao = FakeOccurrenceDao()
            val sched = RecordingScheduler()
            val repoDaManha = TaskRepository(seriesDao, occDao, manhaSeguinte, sched)
            val series = serieDaNoite(manhaSeguinte.instant())
            seriesDao.upsert(series.toEntity())
            val ontem = OccurrenceIds.of(series.id, LocalDate.of(2026, 8, 19))
            occDao.upsert(
                ocorrenciaAdiada(
                    seriesId = series.id,
                    dia = LocalDate.of(2026, 8, 19),
                    lastReminderAt = null,
                    // marcado para ontem 22:00, nunca entregue: 11 h de atraso, janela fechada
                    nextReminderAt = LocalDateTime.of(2026, 8, 19, 22, 0).atZone(zone).toInstant(),
                ).copy(reminderStep = ReminderPolicy.STEP_FIRST).toEntity(),
            )

            repoDaManha.rescheduleAll()

            val stored = occDao.get(ontem)!!.toDomain()
            assertThat(stored.status).isEqualTo(OccurrenceStatus.MISSED)
            assertThat(stored.nextReminderAt).isNull()
            assertThat(sched.cancelled).contains(ontem)
        }
    }

    /**
     * A edição de uma ocorrência que saiu do banco virava no-op **com sucesso**: a linha não
     * está lá, o `editOccurrence` volta sem gravar e quem chamou anuncia "Salvo" para o que
     * ela digitou. Com a última lista boa preservada de propósito, "Editar" numa tarefa
     * apagada por fora perderia o texto dela em silêncio — a classe de defeito que o critério
     * nº 1 do app proíbe. A gravação tem que dizer que não gravou.
     */
    @Test
    fun edicaoDeOcorrenciaSumidaReportaQueNaoGravou() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Cabelo", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)))
        repo.deleteOccurrence(saved.occurrence.id)

        val gravou = repo.editOccurrence(
            saved.occurrence.id,
            "Cabelo",
            LocalDate.of(2026, 8, 21),
            LocalTime.of(9, 0),
            RecurrenceRule(),
        )

        assertThat(gravou).isEqualTo(EditOutcome.GONE)
    }

    /** O caminho normal continua dizendo que gravou. */
    @Test
    fun edicaoDeOcorrenciaVivaReportaQueGravou() = runBlocking {
        val saved = repo.saveDraft(completeDraft("Cabelo", LocalDate.of(2026, 8, 21), LocalTime.of(9, 0)))

        val gravou = repo.editOccurrence(
            saved.occurrence.id,
            "Cabelo",
            LocalDate.of(2026, 8, 22),
            LocalTime.of(10, 0),
            RecurrenceRule(),
        )

        assertThat(gravou).isEqualTo(EditOutcome.SAVED)
    }

    /**
     * Editar para uma data passada não pode ancorar o preview nessa data: as três datas
     * nasciam no passado, o próximo avanço marcava todas como não realizadas e a agenda
     * ficava sem as datas futuras até o app reabrir.
     */
    @Test
    fun editarParaDataPassadaAncoraOPreviewEmHoje() {
        runBlocking {
            val draft = completeDraft("Remédio", LocalDate.of(2026, 8, 20), LocalTime.of(8, 0))
                .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
            val saved = repo.saveDraft(draft)

            repo.editOccurrence(
                saved.occurrence.id,
                "Remédio",
                LocalDate.of(2026, 8, 18),
                LocalTime.of(8, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            val datas = occurrenceDao.forSeries(saved.series.id).map { it.localDate }
            assertThat(datas).contains("2026-08-20")
            assertThat(datas).contains("2026-08-21")
            assertThat(datas).doesNotContain("2026-08-19")
        }
    }

    /** O preview ancorado em hoje continua respeitando a data que o usuário excluiu. */
    @Test
    fun editarNaoRematerializaDataExcluida() {
        runBlocking {
            val draft = completeDraft("Remédio", LocalDate.of(2026, 8, 20), LocalTime.of(8, 0))
                .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
            val saved = repo.saveDraft(draft)
            repo.rescheduleAll()
            val amanha = OccurrenceIds.of(saved.series.id, LocalDate.of(2026, 8, 21))
            assertThat(occurrenceDao.get(amanha)).isNotNull()
            repo.deleteOccurrence(amanha)

            repo.editOccurrence(
                saved.occurrence.id,
                "Remédio",
                LocalDate.of(2026, 8, 18),
                LocalTime.of(8, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            val datas = occurrenceDao.forSeries(saved.series.id).map { it.localDate }
            assertThat(datas).contains("2026-08-20")
            assertThat(datas).doesNotContain("2026-08-21")
        }
    }

    /**
     * Citar de volta para uma data que o usuário havia excluído desfaz a exclusão: manter
     * o tombstone marcado junto com a ocorrência viva bloquearia a data em toda
     * materialização futura.
     */
    @Test
    fun editarParaDataExcluidaDesfazAExclusao() {
        runBlocking {
            val draft = completeDraft("Remédio", LocalDate.of(2026, 8, 20), LocalTime.of(8, 0))
                .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
            val saved = repo.saveDraft(draft)
            repo.rescheduleAll()
            val amanha = OccurrenceIds.of(saved.series.id, LocalDate.of(2026, 8, 21))
            repo.deleteOccurrence(amanha)
            assertThat(seriesDao.get(saved.series.id)!!.toDomain().skippedDates)
                .containsExactly(LocalDate.of(2026, 8, 21))

            repo.editOccurrence(
                saved.occurrence.id,
                "Remédio",
                LocalDate.of(2026, 8, 21),
                LocalTime.of(8, 0),
                RecurrenceRule(RecurrenceKind.DAILY),
            )

            assertThat(seriesDao.get(saved.series.id)!!.toDomain().skippedDates).isEmpty()
            assertThat(occurrenceDao.get(amanha)).isNotNull()
        }
    }

    /**
     * Rede de segurança do receiver: se o tratamento do alarme não terminar a tempo, o
     * mesmo disparo volta daqui a pouco em vez de morrer em silêncio.
     */
    @Test
    fun falhaNoDisparoAgendaRecuperacaoCurta() {
        runBlocking {
            val saved = repo.saveDraft(completeDraft("Remédio", LocalDate.of(2026, 8, 21), LocalTime.of(8, 0)))

            repo.scheduleRecovery(saved.occurrence.id)

            assertThat(scheduler.recovered[saved.occurrence.id])
                .isEqualTo(clock.instant().plusSeconds(TaskRepository.RECOVERY_DELAY_SECONDS))
            assertThat(scheduler.cancelled).doesNotContain(saved.occurrence.id)
        }
    }

    /**
     * A varredura de start lê as séries uma vez e itera com esse retrato. Sem um escritor
     * por vez, o "Excluir" da usuária que cai no meio dela grava o tombstone tarde demais:
     * a varredura ainda não o viu e rematerializa a data excluída.
     */
    @Test
    fun varreduraNaoRessuscitaDataExcluidaEmParalelo() {
        runBlocking {
            val draft = completeDraft("Remédio", LocalDate.of(2026, 8, 20), LocalTime.of(8, 0))
                .copy(recurrence = RecurrenceRule(RecurrenceKind.DAILY))
            val saved = repo.saveDraft(draft)
            repo.rescheduleAll()
            val amanha = OccurrenceIds.of(saved.series.id, LocalDate.of(2026, 8, 21))
            assertThat(occurrenceDao.get(amanha)).isNotNull()

            val varreduraSuspensa = CompletableDeferred<Unit>()
            val liberarVarredura = CompletableDeferred<Unit>()
            occurrenceDao.onFirstGet = {
                varreduraSuspensa.complete(Unit)
                liberarVarredura.await()
            }
            val varredura = launch { repo.rescheduleAll() }
            varreduraSuspensa.await()
            val exclusaoTerminou = CompletableDeferred<Unit>()
            val exclusao = launch {
                repo.deleteOccurrence(amanha)
                exclusaoTerminou.complete(Unit)
            }
            // Um escritor por vez: a exclusão espera a varredura terminar em vez de correr
            // por dentro dela.
            val correuPorDentro = withTimeoutOrNull(200) { exclusaoTerminou.await() } != null
            liberarVarredura.complete(Unit)
            varredura.join()
            exclusao.join()

            assertThat(correuPorDentro).isFalse()
            assertThat(occurrenceDao.get(amanha)).isNull()
            assertThat(occurrenceDao.forSeries(saved.series.id).map { it.localDate })
                .doesNotContain("2026-08-21")
        }
    }

    private fun completeDraft(title: String, date: LocalDate, time: LocalTime) = ParsedTaskDraft(
        title = title,
        localDate = date,
        localTime = time,
        recurrence = RecurrenceRule(RecurrenceKind.NONE),
        confidence = 1.0,
        missingFields = emptySet(),
        ambiguous = false,
        transcript = title,
    )
}

private class RecordingScheduler : AlarmScheduler {
    var exact: Boolean = true
    val scheduled = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val recovered = mutableMapOf<String, Instant>()

    override suspend fun quietHours(): QuietHours = QuietHours()
    override fun canScheduleExact(): Boolean = exact
    override fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean): SchedulerOutcome {
        scheduled += occurrence.id
        return SchedulerOutcome(inexact = !exact, scheduled = true)
    }
    override fun cancel(occurrenceId: String) {
        cancelled += occurrenceId
    }
    override fun scheduleRecovery(occurrenceId: String, at: Instant) {
        recovered[occurrenceId] = at
    }
}

private class FakeSeriesDao : SeriesDao {
    private val rows = linkedMapOf<String, SeriesEntity>()
    private val flow = MutableStateFlow<List<SeriesEntity>>(emptyList())
    private fun emit() { flow.value = rows.values.toList() }
    override suspend fun get(id: String) = rows[id]
    override suspend fun getAll() = rows.values.toList()
    override fun observeAll(): Flow<List<SeriesEntity>> = flow.map { it }
    override suspend fun upsert(entity: SeriesEntity) {
        rows[entity.id] = entity
        emit()
    }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        emit()
        return if (removed) 1 else 0
    }
}

private class FakeOccurrenceDao : OccurrenceDao {
    private val rows = linkedMapOf<String, OccurrenceEntity>()
    private val flow = MutableStateFlow<List<OccurrenceEntity>>(emptyList())
    /** Gancho de teste: suspende a primeira leitura para encaixar outra chamada no meio. */
    var onFirstGet: (suspend () -> Unit)? = null
    private fun emit() { flow.value = rows.values.toList() }
    override suspend fun get(id: String): OccurrenceEntity? {
        onFirstGet?.let { hook ->
            onFirstGet = null
            hook()
        }
        return rows[id]
    }
    override suspend fun forSeries(seriesId: String) = rows.values.filter { it.seriesId == seriesId }
    override suspend fun byStatus(status: String) = rows.values.filter { it.status == status }
    override suspend fun getAll() = rows.values.toList()
    override fun observeAll(): Flow<List<OccurrenceEntity>> = flow.map { it }
    override suspend fun upsert(entity: OccurrenceEntity): Long {
        rows[entity.id] = entity
        emit()
        return 1
    }
    override suspend fun upsertAll(entities: List<OccurrenceEntity>): List<Long> = entities.map { upsert(it) }
    override suspend fun delete(id: String): Int {
        val removed = rows.remove(id) != null
        emit()
        return if (removed) 1 else 0
    }
    override suspend fun deleteSeries(seriesId: String): Int {
        val ids = rows.filterValues { it.seriesId == seriesId }.keys.toList()
        ids.forEach { rows.remove(it) }
        emit()
        return ids.size
    }
}

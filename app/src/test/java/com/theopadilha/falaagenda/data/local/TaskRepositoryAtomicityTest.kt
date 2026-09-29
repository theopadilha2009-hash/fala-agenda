package com.theopadilha.falaagenda.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.repo.AgendaItem
import com.theopadilha.falaagenda.data.repo.SchedulerOutcome
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.domain.model.QuietHours
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskOccurrence
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.recurrence.OccurrenceLifecycle
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import com.theopadilha.falaagenda.reminders.AlarmScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * As escritas que tocam duas tabelas (a ocorrência e a série) precisam ser atômicas: o
 * Android mata o processo no meio com frequência, e o `TaskRepositoryTest` usa DAOs falsos,
 * que não têm transação nenhuma para exercitar. Aqui o banco é Room de verdade e o gatilho
 * derruba a segunda metade da escrita — o teste prova que a primeira não fica para trás.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class TaskRepositoryAtomicityTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val dia = LocalDate.of(2026, 8, 20)
    private val now = LocalDateTime.of(2026, 8, 20, 7, 0).atZone(zone).toInstant()

    private fun abrir(nome: String): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(nome)
        return AppDatabase.create(context, nome)
    }

    private fun serieDiaria() = TaskSeries(
        id = "s1",
        title = "Remédio",
        zoneId = zone,
        localTime = LocalTime.of(8, 0),
        startLocalDate = dia,
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        createdAt = now,
        updatedAt = now,
    )

    private fun AppDatabase.derrubarEscritaCom(sql: String) {
        openHelper.writableDatabase.execSQL(sql)
    }

    /**
     * "Excluir" apaga a ocorrência e grava o tombstone na série. Morrer entre as duas
     * deixava a data excluída sem tombstone — e o próximo start a rematerializava.
     */
    @Test
    fun exclusaoNaoDeixaOcorrenciaApagadaSemTombstone() {
        runBlocking {
            val db = abrir("atomicidade-delete.db")
            try {
                val repo = TaskRepository(
                    db.seriesDao(),
                    db.occurrenceDao(),
                    FixedAppClock(now, zone),
                    NoopScheduler,
                )
                val series = serieDiaria()
                val ocorrencia = OccurrenceLifecycle.materialize(series, dia, now)
                db.seriesDao().upsert(series.toEntity())
                db.occurrenceDao().upsert(ocorrencia.toEntity())
                db.derrubarEscritaCom(
                    "CREATE TRIGGER falha_tombstone BEFORE UPDATE ON task_series " +
                        "WHEN NEW.skippedDates <> '' BEGIN SELECT RAISE(ABORT, 'falha simulada'); END",
                )

                val falha = runCatching { repo.deleteOccurrence(ocorrencia.id) }

                assertThat(falha.isFailure).isTrue()
                assertThat(db.occurrenceDao().get(ocorrencia.id)).isNotNull()
                assertThat(db.seriesDao().get(series.id)!!.toDomain().skippedDates).isEmpty()
            } finally {
                db.close()
            }
        }
    }

    /**
     * "Desfazer o excluir" tira o tombstone e rematerializa a ocorrência. Morrer entre as
     * duas deixava a série sem a ocorrência — para tarefa única ela sumia em definitivo.
     */
    @Test
    fun desfazerExclusaoNaoDeixaSerieSemOcorrencia() {
        runBlocking {
            val db = abrir("atomicidade-restore.db")
            try {
                val repo = TaskRepository(
                    db.seriesDao(),
                    db.occurrenceDao(),
                    FixedAppClock(now, zone),
                    NoopScheduler,
                )
                val series = serieDiaria().copy(skippedDates = setOf(dia))
                val ocorrencia = OccurrenceLifecycle.materialize(series, dia, now)
                db.seriesDao().upsert(series.toEntity())
                db.occurrenceDao().upsert(ocorrencia.toEntity())
                db.derrubarEscritaCom(
                    "CREATE TRIGGER falha_ocorrencia BEFORE INSERT ON task_occurrences " +
                        "BEGIN SELECT RAISE(ABORT, 'falha simulada'); END",
                )

                val falha = runCatching {
                    repo.restore(AgendaItem(ocorrencia, series))
                }

                assertThat(falha.isFailure).isTrue()
                assertThat(db.seriesDao().get(series.id)!!.toDomain().skippedDates).containsExactly(dia)
            } finally {
                db.close()
            }
        }
    }
}

private object NoopScheduler : AlarmScheduler {
    override suspend fun quietHours(): QuietHours = QuietHours()
    override fun canScheduleExact(): Boolean = true
    override fun schedule(occurrence: TaskOccurrence, series: TaskSeries, first: Boolean) =
        SchedulerOutcome(inexact = false, scheduled = true)
    override fun cancel(occurrenceId: String) = Unit
    override fun scheduleRecovery(occurrenceId: String, at: Instant) = Unit
    override fun scheduleDailySweep(at: Instant) = Unit
}

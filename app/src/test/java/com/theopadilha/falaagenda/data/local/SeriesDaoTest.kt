package com.theopadilha.falaagenda.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.domain.model.RecurrenceKind
import com.theopadilha.falaagenda.domain.model.RecurrenceRule
import com.theopadilha.falaagenda.domain.model.TaskSeries
import com.theopadilha.falaagenda.domain.recurrence.OccurrenceLifecycle
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class SeriesDaoTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val now = Instant.parse("2026-09-27T11:00:00Z")

    private fun series(
        id: String = "s1",
        title: String = "Remédio",
        skipped: Set<LocalDate> = emptySet(),
    ) = TaskSeries(
        id = id,
        title = title,
        zoneId = zone,
        localTime = LocalTime.of(8, 0),
        startLocalDate = LocalDate.of(2026, 9, 27),
        recurrence = RecurrenceRule(RecurrenceKind.DAILY),
        skippedDates = skipped,
        createdAt = now,
        updatedAt = now,
    )

    private fun abrir(nome: String): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(nome)
        return AppDatabase.create(context, nome)
    }

    @Test
    fun skippedDatesSobreviveAoBanco() {
        runBlocking {
            val db = abrir("series-skip.db")
            try {
                val dia = LocalDate.of(2026, 9, 27)
                db.seriesDao().upsert(series().toEntity())
                db.seriesDao().upsert(series(skipped = setOf(dia)).toEntity())

                assertThat(db.seriesDao().get("s1")!!.toDomain().skippedDates).containsExactly(dia)
            } finally {
                db.close()
            }
        }
    }

    /**
     * Reescrever a série é rotina (concluir, editar, encerrar). Se o upsert apagar as
     * ocorrências filhas, a agenda do usuário é zerada a cada uma dessas ações.
     */
    @Test
    fun reescreverASerieNaoApagaAsOcorrencias() {
        runBlocking {
            val db = abrir("series-cascade.db")
            try {
                val s = series()
                val occ = OccurrenceLifecycle.materialize(s, LocalDate.of(2026, 9, 27), now)
                db.seriesDao().upsert(s.toEntity())
                db.occurrenceDao().upsert(occ.toEntity())
                assertThat(db.occurrenceDao().get(occ.id)).isNotNull()

                db.seriesDao().upsert(s.copy(title = "Remédio da pressão").toEntity())

                assertThat(db.occurrenceDao().get(occ.id)).isNotNull()
                assertThat(db.occurrenceDao().forSeries("s1")).hasSize(1)
            } finally {
                db.close()
            }
        }
    }
}

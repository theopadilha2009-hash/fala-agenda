package com.theopadilha.falaagenda.reminders

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.theopadilha.falaagenda.data.local.OccurrenceDao
import com.theopadilha.falaagenda.data.local.OccurrenceEntity
import com.theopadilha.falaagenda.data.local.SeriesDao
import com.theopadilha.falaagenda.data.local.SeriesEntity
import com.theopadilha.falaagenda.data.prefs.SettingsStore
import com.theopadilha.falaagenda.data.repo.TaskRepository
import com.theopadilha.falaagenda.domain.time.FixedAppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * O dia não vira sozinho. `android.intent.action.DATE_CHANGED` não está na lista de exceções do
 * broadcast implícito do Android 8, então receiver de manifesto nunca o recebe (com targetSdk
 * 36) — e é por isso que a troca de hora funciona e a meia-noite não. Quem roda a varredura de
 * ciclo de vida na virada é um alarme exato nosso, armado pelo `rescheduleAll` de todo start,
 * boot e troca de hora. Sem ele a ocorrência de ontem fica pendente para sempre e o widget
 * segue anunciando o dia velho.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class DailySweepAlarmTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    @Test
    fun aVarreduraDaViradaDoDiaFicaArmada() {
        runBlocking {
            val agora = LocalDateTime.of(2026, 8, 20, 10, 0).atZone(zone).toInstant()
            val repo = TaskRepository(
                FakeSeriesDao(),
                FakeOccurrenceDao(),
                FixedAppClock(agora, zone),
                ReminderScheduler(context, SettingsStore(context)),
            )

            repo.rescheduleAll()

            val cincoDaMadrugada = LocalDateTime.of(2026, 8, 21, 0, 5).atZone(zone)
                .toInstant().toEpochMilli()
            val armados = shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtTime }
            assertThat(armados).contains(cincoDaMadrugada)
        }
    }

    private class FakeSeriesDao : SeriesDao {
        override suspend fun get(id: String): SeriesEntity? = null
        override suspend fun getAll(): List<SeriesEntity> = emptyList()
        override fun observeAll(): Flow<List<SeriesEntity>> = flowOf(emptyList())
        override suspend fun upsert(entity: SeriesEntity) = Unit
        override suspend fun delete(id: String): Int = 0
    }

    private class FakeOccurrenceDao : OccurrenceDao {
        override suspend fun get(id: String): OccurrenceEntity? = null
        override suspend fun forSeries(seriesId: String): List<OccurrenceEntity> = emptyList()
        override suspend fun byStatus(status: String): List<OccurrenceEntity> = emptyList()
        override suspend fun getAll(): List<OccurrenceEntity> = emptyList()
        override fun observeAll(): Flow<List<OccurrenceEntity>> = flowOf(emptyList())
        override suspend fun upsert(entity: OccurrenceEntity): Long = 1
        override suspend fun upsertAll(entities: List<OccurrenceEntity>): List<Long> = emptyList()
        override suspend fun delete(id: String): Int = 0
        override suspend fun deleteSeries(seriesId: String): Int = 0
    }
}

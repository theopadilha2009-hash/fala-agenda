package com.theopadilha.falaagenda.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    packageName = "com.theopadilha.falaagenda",
    application = Application::class,
)
class MigrationTest {
    @Test
    fun migration1to2TemVersaoCerta() {
        assertThat(AppDatabase.MIGRATION_1_2.startVersion).isEqualTo(1)
        assertThat(AppDatabase.MIGRATION_1_2.endVersion).isEqualTo(2)
        assertThat(AppDatabase.MIGRATE_1_2_SQL).isEqualTo(
            "ALTER TABLE task_series ADD COLUMN amountCents INTEGER",
        )
    }

    @Test
    fun migration2to3TemObservacao() {
        assertThat(AppDatabase.MIGRATION_2_3.startVersion).isEqualTo(2)
        assertThat(AppDatabase.MIGRATION_2_3.endVersion).isEqualTo(3)
        assertThat(AppDatabase.MIGRATE_2_3_SQL).contains("observation")
    }

    @Test
    fun migration3to4TemDatasExcluidas() {
        assertThat(AppDatabase.MIGRATION_3_4.startVersion).isEqualTo(3)
        assertThat(AppDatabase.MIGRATION_3_4.endVersion).isEqualTo(4)
        assertThat(AppDatabase.MIGRATE_3_4_SQL).contains("skippedDates")
        assertThat(AppDatabase.MIGRATE_3_4_SQL).contains("NOT NULL DEFAULT ''")
    }

    /**
     * Sobe um banco v1 de verdade até a v4: as três migrações rodam, o Room valida o
     * schema resultante e a agenda de quem já tem o app não pode perder nada.
     */
    @Test
    fun migracoesDoV1AoV4PreservamDados() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val arquivo = context.getDatabasePath(NOME_BANCO)
            arquivo.parentFile?.mkdirs()
            criarBancoV1(arquivo)

            val db = Room.databaseBuilder(context, AppDatabase::class.java, NOME_BANCO)
                .addMigrations(
                    AppDatabase.MIGRATION_1_2,
                    AppDatabase.MIGRATION_2_3,
                    AppDatabase.MIGRATION_3_4,
                )
                .allowMainThreadQueries()
                .build()
            try {
                val aberto = db.openHelper.writableDatabase
                assertThat(aberto.version).isEqualTo(4)

                aberto.query(
                    "SELECT title, amountCents, observation, skippedDates FROM task_series WHERE id = 's1'",
                ).use { linha ->
                    assertThat(linha.moveToFirst()).isTrue()
                    assertThat(linha.getString(0)).isEqualTo("Vitamina")
                    assertThat(linha.isNull(1)).isTrue()
                    assertThat(linha.getString(2)).isEqualTo("")
                    assertThat(linha.getString(3)).isEqualTo("")
                }

                aberto.query(
                    "SELECT status, reminderStep FROM task_occurrences WHERE id = 's1:2026-09-20'",
                ).use { linha ->
                    assertThat(linha.moveToFirst()).isTrue()
                    assertThat(linha.getString(0)).isEqualTo("PENDING")
                    assertThat(linha.getInt(1)).isEqualTo(0)
                }

                val serie = db.seriesDao().get("s1")!!.toDomain()
                assertThat(serie.title).isEqualTo("Vitamina")
                assertThat(serie.amountCents).isNull()
                assertThat(serie.observation).isEmpty()
                assertThat(serie.skippedDates).isEmpty()

                db.seriesDao().upsert(
                    serie.copy(skippedDates = setOf(LocalDate.of(2026, 9, 27))).toEntity(),
                )
                assertThat(db.seriesDao().get("s1")!!.toDomain().skippedDates)
                    .containsExactly(LocalDate.of(2026, 9, 27))
            } finally {
                db.close()
            }
        }
    }

    private fun criarBancoV1(arquivo: File) {
        arquivo.delete()
        val v1 = SQLiteDatabase.openOrCreateDatabase(arquivo, null)
        v1.execSQL(SQL_SERIES_V1)
        v1.execSQL(SQL_OCCURRENCES_V1)
        v1.execSQL(SQL_INDICE_SERIES)
        v1.execSQL(SQL_INDICE_STATUS)
        v1.execSQL(SQL_INDICE_LOCAL_DATE)
        v1.execSQL(
            "INSERT INTO task_series (id, title, zoneId, localTime, startLocalDate, " +
                "recurrenceKind, weekDays, dayOfMonth, monthOfYear, endedAtEpochMs, " +
                "createdAtEpochMs, updatedAtEpochMs) VALUES ('s1', 'Vitamina', " +
                "'America/Sao_Paulo', '08:00', '2026-09-20', 'DAILY', '', NULL, NULL, NULL, " +
                "1758379200000, 1758379200000)",
        )
        v1.execSQL(
            "INSERT INTO task_occurrences (id, seriesId, localDate, scheduledAtEpochMs, status, " +
                "completedAtEpochMs, missedAtEpochMs, reminderStep, nextReminderAtEpochMs, " +
                "lastReminderAtEpochMs, snoozedUntilEpochMs, inexactAlarm) VALUES " +
                "('s1:2026-09-20', 's1', '2026-09-20', 1758379200000, 'PENDING', NULL, NULL, " +
                "0, 1758379200000, NULL, NULL, 0)",
        )
        v1.version = 1
        v1.close()
    }

    private companion object {
        const val NOME_BANCO = "migracao-v1-v4.db"

        // Esquema da v1, antes de amountCents (1_2), observation (2_3) e skippedDates (3_4).
        const val SQL_SERIES_V1 =
            "CREATE TABLE IF NOT EXISTS `task_series` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`zoneId` TEXT NOT NULL, `localTime` TEXT NOT NULL, `startLocalDate` TEXT NOT NULL, " +
                "`recurrenceKind` TEXT NOT NULL, `weekDays` TEXT NOT NULL, `dayOfMonth` INTEGER, " +
                "`monthOfYear` INTEGER, `endedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, " +
                "`updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        const val SQL_OCCURRENCES_V1 =
            "CREATE TABLE IF NOT EXISTS `task_occurrences` (`id` TEXT NOT NULL, `seriesId` TEXT NOT NULL, " +
                "`localDate` TEXT NOT NULL, `scheduledAtEpochMs` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`completedAtEpochMs` INTEGER, `missedAtEpochMs` INTEGER, `reminderStep` INTEGER NOT NULL, " +
                "`nextReminderAtEpochMs` INTEGER, `lastReminderAtEpochMs` INTEGER, " +
                "`snoozedUntilEpochMs` INTEGER, `inexactAlarm` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`seriesId`) REFERENCES `task_series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

        const val SQL_INDICE_SERIES =
            "CREATE INDEX IF NOT EXISTS `index_task_occurrences_seriesId` ON `task_occurrences` (`seriesId`)"
        const val SQL_INDICE_STATUS =
            "CREATE INDEX IF NOT EXISTS `index_task_occurrences_status` ON `task_occurrences` (`status`)"
        const val SQL_INDICE_LOCAL_DATE =
            "CREATE INDEX IF NOT EXISTS `index_task_occurrences_localDate` ON `task_occurrences` (`localDate`)"
    }
}

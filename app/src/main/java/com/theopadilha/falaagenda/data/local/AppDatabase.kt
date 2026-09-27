package com.theopadilha.falaagenda.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SeriesEntity::class, OccurrenceEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seriesDao(): SeriesDao
    abstract fun occurrenceDao(): OccurrenceDao

    companion object {
        const val MIGRATE_1_2_SQL = "ALTER TABLE task_series ADD COLUMN amountCents INTEGER"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MIGRATE_1_2_SQL)
            }
        }

        const val MIGRATE_2_3_SQL =
            "ALTER TABLE task_series ADD COLUMN observation TEXT NOT NULL DEFAULT ''"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MIGRATE_2_3_SQL)
            }
        }

        const val MIGRATE_3_4_SQL =
            "ALTER TABLE task_series ADD COLUMN skippedDates TEXT NOT NULL DEFAULT ''"

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(MIGRATE_3_4_SQL)
            }
        }

        fun create(context: Context, name: String = "fala_agenda.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}

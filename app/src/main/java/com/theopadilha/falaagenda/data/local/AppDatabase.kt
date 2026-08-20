package com.theopadilha.falaagenda.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SeriesEntity::class, OccurrenceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seriesDao(): SeriesDao
    abstract fun occurrenceDao(): OccurrenceDao

    companion object {
        fun create(context: Context, name: String = "fala_agenda.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}

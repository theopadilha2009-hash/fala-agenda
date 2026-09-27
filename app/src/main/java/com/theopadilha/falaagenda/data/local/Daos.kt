package com.theopadilha.falaagenda.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM task_series WHERE id = :id")
    suspend fun get(id: String): SeriesEntity?

    @Query("SELECT * FROM task_series")
    suspend fun getAll(): List<SeriesEntity>

    @Query("SELECT * FROM task_series")
    fun observeAll(): Flow<List<SeriesEntity>>

    /**
     * @Upsert (INSERT ... ON CONFLICT DO UPDATE), nunca INSERT OR REPLACE: REPLACE apaga a
     * linha antiga e a FK de task_occurrences tem ON DELETE CASCADE — reescrever a série
     * zerava as ocorrências dela.
     */
    @Upsert
    suspend fun upsert(entity: SeriesEntity)

    @Query("DELETE FROM task_series WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM task_occurrences WHERE id = :id")
    suspend fun get(id: String): OccurrenceEntity?

    @Query("SELECT * FROM task_occurrences WHERE seriesId = :seriesId")
    suspend fun forSeries(seriesId: String): List<OccurrenceEntity>

    @Query("SELECT * FROM task_occurrences WHERE status = :status")
    suspend fun byStatus(status: String): List<OccurrenceEntity>

    @Query("SELECT * FROM task_occurrences")
    suspend fun getAll(): List<OccurrenceEntity>

    @Query("SELECT * FROM task_occurrences")
    fun observeAll(): Flow<List<OccurrenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OccurrenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<OccurrenceEntity>): List<Long>

    @Query("DELETE FROM task_occurrences WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM task_occurrences WHERE seriesId = :seriesId")
    suspend fun deleteSeries(seriesId: String): Int
}

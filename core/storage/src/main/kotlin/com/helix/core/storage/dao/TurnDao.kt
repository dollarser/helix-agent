package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.TurnEntity

@Dao
interface TurnDao {
    @Query(
        "SELECT * FROM turns WHERE resultCollectedAt IS NULL " +
            "OR state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED') " +
            "ORDER BY startedAt DESC, rowid DESC",
    )
    fun pendingTasks(): List<TurnEntity>

    @Query("SELECT * FROM turns ORDER BY startedAt DESC, rowid DESC LIMIT :limit")
    fun recent(limit: Int): List<TurnEntity>

    @Query(
        "UPDATE turns SET resultCollectedAt = :now WHERE id = :id AND resultCollectedAt IS NULL " +
            "AND state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')",
    )
    fun collectResult(
        id: String,
        now: Long,
    ): Int

    @Query(
        "UPDATE turns SET pauseRequestedAt = :now WHERE id = :id AND pauseRequestedAt IS NULL " +
            "AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')",
    )
    fun requestPause(
        id: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(turn: TurnEntity)

    @Query("SELECT * FROM turns WHERE id = :id")
    fun byId(id: String): TurnEntity?

    @Query("SELECT * FROM turns WHERE sessionId = :sessionId ORDER BY startedAt ASC, rowid ASC")
    fun listBySession(sessionId: String): List<TurnEntity>

    /** Non-terminal turns left by a previous process — the HXA-015 recovery scan. */
    @Query(
        "SELECT * FROM turns WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
            "ORDER BY startedAt ASC, rowid ASC",
    )
    fun listActive(): List<TurnEntity>

    @Query(
        "UPDATE turns SET state = :state, stepCount = :stepCount, endedAt = :endedAt, " +
            "errorCode = :errorCode WHERE id = :id",
    )
    fun updateState(
        id: String,
        state: String,
        stepCount: Int,
        endedAt: Long?,
        errorCode: String?,
    )
}

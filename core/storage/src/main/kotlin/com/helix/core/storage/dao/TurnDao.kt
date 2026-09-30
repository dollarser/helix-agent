package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.TurnEntity

@Dao
@Suppress("TooManyFunctions") // One Turn query/CAS authority, including batched startup inspection.
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
            "AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'NEEDS_REVIEW', 'INTERRUPTED')",
    )
    fun requestPause(
        id: String,
        now: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(turn: TurnEntity)

    @Query("SELECT * FROM turns WHERE id = :id")
    fun byId(id: String): TurnEntity?

    /** The turn a [clientRequestId] already started (submit dedup, HX2-01 §2e); unique index ⇒ at most one. */
    @Query("SELECT * FROM turns WHERE clientRequestId = :clientRequestId LIMIT 1")
    fun byClientRequestId(clientRequestId: String): TurnEntity?

    @Query("SELECT * FROM turns WHERE sessionId = :sessionId ORDER BY startedAt ASC, rowid ASC")
    fun listBySession(sessionId: String): List<TurnEntity>

    /** Latest row per live conversation; avoids loading every historical Turn at startup. */
    @Query(
        "SELECT t.* FROM turns t JOIN sessions s ON s.id = t.sessionId " +
            "WHERE s.archivedAt IS NULL AND t.rowid = " +
            "(SELECT rowid FROM turns WHERE sessionId = t.sessionId ORDER BY startedAt DESC, rowid DESC LIMIT 1) " +
            "ORDER BY t.startedAt ASC, t.rowid ASC",
    )
    fun latestForUnarchivedSessions(): List<TurnEntity>

    /** Non-terminal turns left by a previous process — the HXA-015 recovery scan. */
    @Query(
        "SELECT * FROM turns WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED') " +
            "ORDER BY startedAt ASC, rowid ASC",
    )
    fun listActive(): List<TurnEntity>

    @Query(
        "UPDATE turns SET state = :state, stepCount = :stepCount, endedAt = :endedAt, " +
            "errorCode = :errorCode WHERE id = :id AND state = :expectedState AND stepCount = :expectedStepCount",
    )
    fun updateState(
        id: String,
        expectedState: String,
        expectedStepCount: Int,
        state: String,
        stepCount: Int,
        endedAt: Long?,
        errorCode: String?,
    ): Int
}

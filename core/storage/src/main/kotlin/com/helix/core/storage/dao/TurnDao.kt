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

    @Query("SELECT * FROM turns WHERE id IN (:ids) ORDER BY startedAt ASC, rowid ASC")
    fun byIds(ids: List<String>): List<TurnEntity>

    @Query(
        "SELECT * FROM turns WHERE sessionId = :sessionId AND state NOT IN (:settledStates) " +
            "ORDER BY startedAt ASC, rowid ASC",
    )
    fun unsettled(
        sessionId: String,
        settledStates: List<String>,
    ): List<TurnEntity>

    @Query(
        "SELECT t.* FROM turns t WHERE t.sessionId = :sessionId " +
            "AND (:state IS NULL OR t.state = :state) AND NOT EXISTS " +
            "(SELECT 1 FROM messages m WHERE m.sessionId = :sessionId AND m.turnId = t.id " +
            "AND m.supersededBy IS NOT NULL) ORDER BY t.startedAt DESC, t.rowid DESC LIMIT 1",
    )
    fun latestVisible(
        sessionId: String,
        state: String?,
    ): TurnEntity?

    @Query(
        "SELECT * FROM turns WHERE sessionId = :sessionId AND state IN (:states) " +
            "ORDER BY startedAt DESC, rowid DESC LIMIT :limit",
    )
    fun recentByStates(
        sessionId: String,
        states: List<String>,
        limit: Int,
    ): List<TurnEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM turns newer JOIN turns original ON original.id = :turnId " +
            "WHERE newer.sessionId = original.sessionId AND newer.state = 'COMPLETED' " +
            "AND (newer.startedAt > original.startedAt OR " +
            "(newer.startedAt = original.startedAt AND newer.rowid > original.rowid)))",
    )
    fun hasLaterCompleted(turnId: String): Boolean

    @Query(
        "SELECT t.* FROM turns t WHERE t.sessionId = :sessionId AND EXISTS " +
            "(SELECT 1 FROM tool_calls c WHERE c.turnId = t.id AND c.name = 'todo.write' " +
            "AND c.state = 'COMPLETED') ORDER BY t.startedAt DESC, t.rowid DESC LIMIT :limit OFFSET :offset",
    )
    fun ledgerCandidates(
        sessionId: String,
        offset: Int,
        limit: Int,
    ): List<TurnEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM turns t WHERE t.id = :turnId AND t.rowid = " +
            "(SELECT rowid FROM turns WHERE sessionId = t.sessionId ORDER BY startedAt DESC, rowid DESC LIMIT 1) " +
            "AND NOT EXISTS (SELECT 1 FROM tool_calls c JOIN turns prior ON prior.id = c.turnId " +
            "WHERE prior.sessionId = t.sessionId AND c.state NOT IN ('COMPLETED','FAILED','DENIED','CANCELLED')))",
    )
    fun budgetContinuationClear(turnId: String): Boolean

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

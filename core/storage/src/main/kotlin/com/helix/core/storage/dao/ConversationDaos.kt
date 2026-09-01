package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ApprovalEntity
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.storage.entity.ExecutionEntity
import com.helix.core.storage.entity.InteractionReceiptEntity
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.ModelCallEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.core.storage.entity.TurnEntity

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun list(): List<SessionEntity>

    /** Affected row count is 0 when the session is missing or already archived. */
    @Query("UPDATE sessions SET archivedAt = :archivedAt WHERE id = :id AND archivedAt IS NULL")
    fun archive(
        id: String,
        archivedAt: Long,
    ): Int
    // No delete query: sessions are archived, never deleted (doc 9.1). Deleting would cascade
    // the session's approval/execution audit rows, which must remain durable.
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sequence ASC")
    fun listBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM messages WHERE sessionId = :sessionId")
    fun maxSequence(sessionId: String): Long
}

@Dao
interface TurnDao {
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

@Dao
interface ModelCallDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(call: ModelCallEntity)

    @Query("SELECT * FROM model_calls WHERE id = :id")
    fun byId(id: String): ModelCallEntity?

    @Query("SELECT * FROM model_calls WHERE turnId = :turnId ORDER BY rowid ASC")
    fun listByTurn(turnId: String): List<ModelCallEntity>

    @Query("UPDATE model_calls SET state = :state, usage = :usage, requestId = :requestId WHERE id = :id")
    fun update(
        id: String,
        state: String,
        usage: String?,
        requestId: String?,
    )
}

@Dao
interface ToolCallDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(call: ToolCallEntity)

    @Query("SELECT * FROM tool_calls WHERE id = :id")
    fun byId(id: String): ToolCallEntity?

    @Query("SELECT * FROM tool_calls WHERE turnId = :turnId ORDER BY rowid ASC")
    fun listByTurn(turnId: String): List<ToolCallEntity>

    @Query("SELECT * FROM tool_calls WHERE turnId = :turnId AND callId = :callId")
    fun byTurnAndCallId(
        turnId: String,
        callId: String,
    ): ToolCallEntity?

    @Query("UPDATE tool_calls SET state = :state WHERE id = :id")
    fun updateState(
        id: String,
        state: String,
    )
}

@Dao
interface ToolResultDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(result: ToolResultEntity)

    @Query("SELECT * FROM tool_results WHERE id = :id")
    fun byId(id: String): ToolResultEntity?

    @Query("SELECT * FROM tool_results WHERE toolCallId = :toolCallId")
    fun byToolCall(toolCallId: String): ToolResultEntity?

    /** Affected row count is 0 once the result is already verified. */
    @Query("UPDATE tool_results SET verified = 1 WHERE id = :id AND verified = 0")
    fun markVerified(id: String): Int
}

@Dao
interface ApprovalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(approval: ApprovalEntity)

    @Query("SELECT * FROM approvals WHERE id = :id")
    fun byId(id: String): ApprovalEntity?

    @Query("SELECT * FROM approvals WHERE toolCallId = :toolCallId")
    fun byToolCall(toolCallId: String): ApprovalEntity?

    /** One-time closed decision: affected row count is 0 for unknown or already-decided values. */
    @Query(
        "UPDATE approvals SET decision = :decision, decidedAt = :decidedAt " +
            "WHERE id = :id AND decision IS NULL AND :decision IN ('APPROVED', 'DENIED')",
    )
    fun decide(
        id: String,
        decision: String,
        decidedAt: Long,
    ): Int

    /**
     * One-time, binding-checked consumption: affected row count is 1 only when the record is
     * APPROVED, not yet consumed, not expired at [now], and the stored binding hash matches
     * the proof's hash. Pending, DENIED, expired, already-consumed and mismatched-hash
     * consumptions all return 0 — enforced in SQL, never in caller pre-checks (HXA-034).
     */
    @Query(
        "UPDATE approvals SET consumedAt = :consumedAt " +
            "WHERE id = :id AND consumedAt IS NULL AND decision = 'APPROVED' " +
            "AND expiresAt > :now AND bindingHash = :bindingHash",
    )
    fun consumeByBinding(
        id: String,
        bindingHash: String,
        consumedAt: Long,
        now: Long,
    ): Int

    /**
     * One-time refund of a consumed proof (roadmap HXA-037; doc 11 section 3.3): the
     * consumption is annulled ONLY when the record is APPROVED and currently consumed and
     * the binding hash matches the proof. A second refund — or a refund of an
     * unconsumed / non-APPROVED / mismatched record — affects 0 rows (enforced in SQL).
     * The refund grants nothing by itself: the record must still pass the mint guards.
     */
    @Query(
        "UPDATE approvals SET consumedAt = NULL " +
            "WHERE id = :id AND consumedAt IS NOT NULL AND decision = 'APPROVED' " +
            "AND bindingHash = :bindingHash",
    )
    fun refundByBinding(
        id: String,
        bindingHash: String,
    ): Int
}

/**
 * doc 11 section 4 (roadmap HXA-037): structured user questions with one-time receipts.
 * Every transition is a guarded one-time UPDATE (affected-row-count checked in SQL); a
 * late, duplicate, cancelled, superseded or expired answer affects 0 rows and is mapped
 * to a stable NOT_PENDING reason by the repository.
 */
@Dao
interface InteractionReceiptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(receipt: InteractionReceiptEntity)

    @Query("SELECT * FROM interaction_receipts WHERE id = :id")
    fun byId(id: String): InteractionReceiptEntity?

    @Query(
        "SELECT * FROM interaction_receipts WHERE sessionId = :sessionId AND state = 'PENDING' " +
            "AND expiresAt > :now ORDER BY createdAt ASC, rowid ASC",
    )
    fun pending(
        sessionId: String,
        now: Long,
    ): List<InteractionReceiptEntity>

    @Query(
        "SELECT * FROM interaction_receipts WHERE sessionId = :sessionId " +
            "ORDER BY createdAt DESC, rowid DESC LIMIT :limit",
    )
    fun recent(
        sessionId: String,
        limit: Int,
    ): List<InteractionReceiptEntity>

    /** One-time answer: PENDING and unexpired at [now]. */
    @Query(
        "UPDATE interaction_receipts SET state = 'ANSWERED', answerHash = :answerHash, " +
            "answeredAt = :answeredAt WHERE id = :id AND state = 'PENDING' AND expiresAt > :now",
    )
    fun answer(
        id: String,
        answerHash: String,
        answeredAt: Long,
        now: Long,
    ): Int

    /** One-time cancel: PENDING only. */
    @Query("UPDATE interaction_receipts SET state = 'CANCELLED' WHERE id = :id AND state = 'PENDING'")
    fun cancel(id: String): Int

    /** A newer version of the same request supersedes the older pending receipts. */
    @Query(
        "UPDATE interaction_receipts SET state = 'SUPERSEDED' " +
            "WHERE requestId = :requestId AND version < :version AND state = 'PENDING'",
    )
    fun supersedeOlder(
        requestId: String,
        version: Int,
    ): Int
}

@Dao
interface ExecutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(execution: ExecutionEntity)

    @Query("SELECT * FROM executions WHERE id = :id")
    fun byId(id: String): ExecutionEntity?

    @Query("SELECT * FROM executions WHERE toolCallId = :toolCallId")
    fun byToolCall(toolCallId: String): ExecutionEntity?

    @Query("UPDATE executions SET exitCode = :exitCode, signal = :signal WHERE id = :id")
    fun updateOutcome(
        id: String,
        exitCode: Int?,
        signal: String?,
    )
}

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(artifact: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE id = :id")
    fun byId(id: String): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId ORDER BY rowid ASC")
    fun listBySession(sessionId: String): List<ArtifactEntity>
}

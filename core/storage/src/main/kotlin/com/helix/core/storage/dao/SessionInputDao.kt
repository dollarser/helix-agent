package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.helix.core.storage.entity.SessionInputAttachmentEntity
import com.helix.core.storage.entity.SessionInputEntity

/** Repository owns the transaction covering reads, bounds, revisions and writes. */
@Dao
@Suppress("TooManyFunctions") // Room requires explicit bounded reads and writes for both related tables.
interface SessionInputDao {
    @Query("SELECT * FROM session_inputs WHERE inputId = :id")
    fun byId(id: String): SessionInputEntity?

    /** Exact producer family, not LIKE: ids containing SQL wildcard characters stay literal. */
    @Query(
        "SELECT * FROM session_inputs WHERE sessionId = :sessionId " +
            "AND (inputId = :baseId OR instr(inputId, :baseId || ':retry:') = 1) " +
            "ORDER BY sequence DESC LIMIT 1",
    )
    fun latestAttempt(
        sessionId: String,
        baseId: String,
    ): SessionInputEntity?

    /** Answer delivery changes independently of the chat timeline. */
    @Query(
        "SELECT COALESCE(SUM(revision + 1), 0) FROM session_inputs " +
            "WHERE sessionId = :sessionId AND substr(inputId, 1, 7) = 'answer:'",
    )
    fun observeAnswerRevision(sessionId: String): kotlinx.coroutines.flow.Flow<Long>

    /** Includes request publication, which does not change the editable input revision. */
    @Query(
        "SELECT COALESCE(SUM(revision + 1), 0) + COUNT(requestModelCallId) FROM session_inputs " +
            "WHERE sessionId = :sessionId",
    )
    fun observeDeliveryRevision(sessionId: String): kotlinx.coroutines.flow.Flow<Long>

    @Insert
    fun insert(input: SessionInputEntity)

    @Update
    fun update(input: SessionInputEntity): Int

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM session_inputs WHERE sessionId = :sessionId")
    fun lastSequence(sessionId: String): Long

    @Query(
        "SELECT COUNT(*) FROM session_inputs WHERE sessionId = :sessionId AND state IN ('PENDING','NEEDS_ATTENTION')",
    )
    fun pendingCount(sessionId: String): Int

    @Query(
        "SELECT COALESCE(SUM(textBytes + referenceContentBytes), 0) FROM session_inputs WHERE sessionId = :sessionId " +
            "AND state IN ('PENDING','NEEDS_ATTENTION')",
    )
    fun pendingBytes(sessionId: String): Long

    @Query(
        "SELECT * FROM session_inputs WHERE sessionId = :sessionId " +
            "AND state IN ('PENDING','NEEDS_ATTENTION') ORDER BY sequence LIMIT :limit",
    )
    fun listPending(
        sessionId: String,
        limit: Int,
    ): List<SessionInputEntity>

    @Query(
        "SELECT * FROM session_inputs WHERE sessionId = :sessionId AND state = 'APPENDED' " +
            "ORDER BY sequence DESC LIMIT :limit",
    )
    fun recentAppended(
        sessionId: String,
        limit: Int,
    ): List<SessionInputEntity>

    @Query(
        "SELECT * FROM session_inputs WHERE sessionId = :sessionId AND delivery = 'QUEUE' " +
            "AND state IN ('PENDING','NEEDS_ATTENTION') ORDER BY sequence LIMIT 1",
    )
    fun headQueue(sessionId: String): SessionInputEntity?

    @Query(
        "SELECT * FROM session_inputs WHERE sessionId = :sessionId AND delivery = 'STEER' " +
            "AND expectedTurnId = :turnId AND state IN ('PENDING','NEEDS_ATTENTION') ORDER BY sequence LIMIT 1",
    )
    fun headSteer(
        sessionId: String,
        turnId: String,
    ): SessionInputEntity?

    @Query(
        "SELECT * FROM session_inputs WHERE consumedTurnId = :turnId AND state = 'APPENDED' " +
            "AND requestModelCallId IS NULL ORDER BY sequence LIMIT :limit",
    )
    fun pendingRequest(
        turnId: String,
        limit: Int,
    ): List<SessionInputEntity>

    @Query(
        "SELECT * FROM session_inputs WHERE consumedTurnId = :turnId AND messageId IN (:messageIds) " +
            "AND state = 'APPENDED' ORDER BY sequence",
    )
    fun appendedForMessages(
        turnId: String,
        messageIds: List<String>,
    ): List<SessionInputEntity>

    @Insert
    fun insertAttachments(attachments: List<SessionInputAttachmentEntity>)

    @Query("SELECT * FROM session_input_attachments WHERE inputId = :inputId ORDER BY ordinal")
    fun attachments(inputId: String): List<SessionInputAttachmentEntity>

    @Query("DELETE FROM session_input_attachments WHERE inputId = :inputId")
    fun deleteAttachments(inputId: String)

    @Query(
        "UPDATE session_inputs SET state = 'NEEDS_ATTENTION', blockedReason = :reason, " +
            "revision = revision + 1, updatedAt = MAX(updatedAt, :at) " +
            "WHERE sessionId = :sessionId AND state = 'PENDING'",
    )
    fun parkSession(
        sessionId: String,
        reason: String,
        at: Long,
    ): Int

    @Query(
        "UPDATE session_inputs SET state = 'NEEDS_ATTENTION', blockedReason = :reason, " +
            "revision = revision + 1, updatedAt = MAX(updatedAt, :at) WHERE state = 'PENDING'",
    )
    fun parkAll(
        reason: String,
        at: Long,
    ): Int

    @Query(
        "SELECT textRef FROM session_inputs WHERE sessionId = :sessionId " +
            "UNION SELECT referenceContentRef FROM session_inputs " +
            "WHERE sessionId = :sessionId AND referenceContentRef IS NOT NULL",
    )
    fun contentRefsBySession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM session_inputs WHERE textRef = :contentRef OR referenceContentRef = :contentRef")
    fun countByContentRef(contentRef: String): Int
}

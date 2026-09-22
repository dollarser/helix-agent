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
        "SELECT COALESCE(SUM(textBytes), 0) FROM session_inputs WHERE sessionId = :sessionId " +
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

    @Query("SELECT textRef FROM session_inputs WHERE sessionId = :sessionId")
    fun contentRefsBySession(sessionId: String): List<String>

    @Query("SELECT COUNT(*) FROM session_inputs WHERE textRef = :contentRef")
    fun countByContentRef(contentRef: String): Int
}

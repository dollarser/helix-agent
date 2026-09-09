package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.InteractionReceiptEntity

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

    @Query("DELETE FROM interaction_receipts WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String): Int

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

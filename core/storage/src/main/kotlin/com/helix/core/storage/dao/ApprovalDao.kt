package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ApprovalEntity

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

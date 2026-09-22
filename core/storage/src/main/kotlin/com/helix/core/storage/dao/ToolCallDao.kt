package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolCallEntity

@Dao
interface ToolCallDao {
    @Query(
        "SELECT c.* FROM tool_calls c JOIN turns t ON c.turnId = t.id " +
            "WHERE t.state IN ('COMPLETED', 'FAILED', 'CANCELLED') AND c.state != 'NEEDS_REVIEW' " +
            "AND (c.state IN ('PENDING', 'RUNNING', 'AWAITING_APPROVAL', 'INTERRUPTED') " +
            "OR NOT EXISTS (SELECT 1 FROM tool_results r WHERE r.toolCallId = c.id) " +
            "OR (c.state = 'COMPLETED' AND EXISTS " +
            "(SELECT 1 FROM tool_results r WHERE r.toolCallId = c.id AND r.verified = 0)))",
    )
    fun unsettledUnderTerminalTurns(): List<ToolCallEntity>

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

    /** Pending background work survives the recent-Turn window; settled history stays bounded. */
    @Query(
        "SELECT c.* FROM tool_calls c WHERE c.name = 'code.linux.job.start' " +
            "AND EXISTS (SELECT 1 FROM audit_events p WHERE p.id = 'proot-job-' || c.callId " +
            "AND p.type = 'proot.job_prepared' AND p.actor = 'platform') " +
            "AND (((c.state NOT IN ('FAILED', 'DENIED') OR EXISTS " +
            "(SELECT 1 FROM audit_events t WHERE t.id = 'proot-terminal-' || c.callId " +
            "AND t.type = 'proot.job_terminal' AND t.actor = 'platform')) AND NOT EXISTS " +
            "(SELECT 1 FROM audit_events s WHERE s.id = 'proot-settled-' || c.callId " +
            "AND s.type = 'proot.job_settled' AND s.actor = 'platform') AND NOT EXISTS " +
            "(SELECT 1 FROM audit_events d WHERE d.id = 'proot-disposed-' || c.callId " +
            "AND d.type = 'proot.job_disposed' AND d.actor = 'platform')) " +
            "OR c.id IN (SELECT id FROM tool_calls WHERE name = 'code.linux.job.start' " +
            "ORDER BY rowid DESC LIMIT :recentLimit)) ORDER BY c.rowid DESC",
    )
    fun detachedJobCandidates(recentLimit: Int): List<ToolCallEntity>

    @Query("UPDATE tool_calls SET state = :state WHERE id = :id")
    fun updateState(
        id: String,
        state: String,
    )
}

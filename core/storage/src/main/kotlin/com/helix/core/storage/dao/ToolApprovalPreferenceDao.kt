package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolApprovalPreferenceEntity

/**
 * Durable user tool-approval preferences (HXA-200, ADR-0052). Rows are created and removed only
 * through [com.helix.core.storage.repository.ToolApprovalPreferenceRepository] (the user
 * application service) — the model, Skill, MCP and A2A never touch this DAO. Reads are the bounded
 * per-tool identity query [byTool]; the Policy/Dispatcher side resolves the applicable rows into a
 * single effective preference and never mutates them here.
 */
@Dao
interface ToolApprovalPreferenceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ToolApprovalPreferenceEntity)

    /**
     * Updates an existing row in place (stable [ToolApprovalPreferenceEntity.id]) and advances the
     * revision. The caller reads the current row first and passes `revision + 1`.
     */
    @Query(
        "UPDATE tool_approval_preferences " +
            "SET preference = :preference, contractHash = :contractHash, " +
            "revision = :revision, updatedAtEpoch = :updatedAtEpoch " +
            "WHERE id = :id",
    )
    fun update(
        id: String,
        preference: String,
        contractHash: String,
        revision: Long,
        updatedAtEpoch: Long,
    )

    @Query(
        "SELECT * FROM tool_approval_preferences " +
            "WHERE sourceRef = :sourceRef AND toolName = :toolName " +
            "AND scopeKind = :scopeKind AND scopeRef = :scopeRef",
    )
    fun byKey(
        sourceRef: String,
        toolName: String,
        scopeKind: String,
        scopeRef: String,
    ): ToolApprovalPreferenceEntity?

    /** Every stored preference for one tool identity, across all scopes; deterministic order. */
    @Query(
        "SELECT * FROM tool_approval_preferences " +
            "WHERE sourceRef = :sourceRef AND toolName = :toolName " +
            "ORDER BY scopeKind, rowid",
    )
    fun byTool(
        sourceRef: String,
        toolName: String,
    ): List<ToolApprovalPreferenceEntity>

    /** "Reset to default" is a delete, not a fourth state (ADR-0052 point 5). */
    @Query(
        "DELETE FROM tool_approval_preferences " +
            "WHERE sourceRef = :sourceRef AND toolName = :toolName " +
            "AND scopeKind = :scopeKind AND scopeRef = :scopeRef",
    )
    fun deleteByScope(
        sourceRef: String,
        toolName: String,
        scopeKind: String,
        scopeRef: String,
    ): Int
}

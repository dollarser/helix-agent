package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolAvailabilityEntity

/**
 * Stored tool availability states (HXA-209). Rows are written only through
 * [com.helix.core.storage.repository.ToolAvailabilityRepository]; [insert] is a REPLACE upsert
 * under the composite primary key (the repository reads the current row first and passes the
 * advanced revision). "Reset to default" is [deleteByKey] — a row delete, not a third state.
 */
@Dao
interface ToolAvailabilityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: ToolAvailabilityEntity)

    @Query(
        "SELECT * FROM tool_availability " +
            "WHERE sourceRef = :sourceRef AND toolName = :toolName " +
            "AND scopeKind = :scopeKind AND scopeRef = :scopeRef",
    )
    fun byKey(
        sourceRef: String,
        toolName: String,
        scopeKind: String,
        scopeRef: String,
    ): ToolAvailabilityEntity?

    /** Every stored state for one tool identity, across all scopes; deterministic order. */
    @Query(
        "SELECT * FROM tool_availability " +
            "WHERE sourceRef = :sourceRef AND toolName = :toolName " +
            "ORDER BY scopeKind, rowid",
    )
    fun byTool(
        sourceRef: String,
        toolName: String,
    ): List<ToolAvailabilityEntity>

    @Query(
        "DELETE FROM tool_availability " +
            "WHERE sourceRef = :sourceRef AND toolName = :toolName " +
            "AND scopeKind = :scopeKind AND scopeRef = :scopeRef",
    )
    fun deleteByKey(
        sourceRef: String,
        toolName: String,
        scopeKind: String,
        scopeRef: String,
    ): Int

    /** Every stored state, deterministic order; the settings surfaces read through this. */
    @Query(
        "SELECT * FROM tool_availability " +
            "ORDER BY sourceRef, toolName, scopeKind, scopeRef",
    )
    fun all(): List<ToolAvailabilityEntity>
}

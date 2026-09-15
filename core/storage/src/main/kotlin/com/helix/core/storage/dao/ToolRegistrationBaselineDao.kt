package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolRegistrationBaselineEntity

/**
 * Trusted tool-registration/upgrade baseline markers (HXA-200, ADR-0052 point 1). Rows are created
 * ONLY by the trusted app registration path through
 * [com.helix.core.storage.repository.ToolRegistrationBaselineRepository] — the model, Skill, MCP,
 * A2A and the UI never touch this DAO. The read is the bounded per-tool [firstSeenVersionCode]
 * lookup the resolver's "is this tool new?" decision consumes.
 */
@Dao
interface ToolRegistrationBaselineDao {
    /**
     * First-write-wins insert: a tool that already has a marker keeps its original
     * [ToolRegistrationBaselineEntity.firstSeenVersionCode] (the marker is stable across restarts
     * and upgrades), so re-registration never re-stamps a tool as newly seen.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(entity: ToolRegistrationBaselineEntity)

    /** The versionCode a tool was first trustedly seen at, or null when it has no marker yet. */
    @Query(
        "SELECT firstSeenVersionCode FROM tool_registration_baseline " +
            "WHERE sourceRef = :sourceRef AND toolName = :toolName",
    )
    fun firstSeenVersionCode(
        sourceRef: String,
        toolName: String,
    ): Long?
}

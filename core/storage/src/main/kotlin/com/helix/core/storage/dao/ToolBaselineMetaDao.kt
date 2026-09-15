package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.ToolBaselineMetaEntity

/**
 * The single-row trusted-baseline anchor (HXA-200, ADR-0052 point 1). Written first-write-wins only
 * by the trusted app registration path through
 * [com.helix.core.storage.repository.ToolRegistrationBaselineRepository]; the model, Skill, MCP, A2A
 * and the UI never touch this DAO.
 */
@Dao
interface ToolBaselineMetaDao {
    /**
     * First-write-wins: the first registration run records the founding versionCode; every later
     * run under the constant [ToolBaselineMetaEntity.BASELINE_ROW_ID] is ignored, so the anchor is
     * stable across restarts and upgrades.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(entity: ToolBaselineMetaEntity)

    /** The anchor row, or null before the baseline has ever been established (a fresh DB). */
    @Query("SELECT * FROM tool_baseline_meta WHERE id = :id")
    fun byId(id: String): ToolBaselineMetaEntity?
}

package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single-row anchor of the trusted tool-registration/upgrade baseline (HXA-200, ADR-0052
 * point 1; 2026-09-15 mechanism addendum). [foundingVersionCode] is the app versionCode at which
 * this device's baseline was FIRST established — the version that was already shipping when the
 * preference feature first ran here. It is written first-write-wins (an [androidx.room.OnConflictStrategy.IGNORE]
 * insert under the constant [BASELINE_ROW_ID]): the very first registration run records the
 * current versionCode, and every later run leaves it alone, so the anchor is stable across
 * restarts and app upgrades.
 *
 * This is what makes a FRESH INSTALL treat every bundled tool as OLD (founding == current, so no
 * tool is "newer than the founding"), while a later UPGRADE that introduces a tool makes that tool
 * "new" (its [ToolRegistrationBaselineEntity.firstSeenVersionCode] equals the new current version
 * and is greater than the founding). Only the trusted app registration path writes this row; the
 * model, Skill, MCP, A2A and the UI never can (ADR-0052 point 1).
 */
@Entity(tableName = "tool_baseline_meta")
data class ToolBaselineMetaEntity(
    @PrimaryKey val id: String,
    val foundingVersionCode: Long,
    val updatedAtEpoch: Long,
) {
    companion object {
        /** The only id this table ever holds: it is a single-row meta record, not a per-tool list. */
        const val BASELINE_ROW_ID = "baseline"
    }
}

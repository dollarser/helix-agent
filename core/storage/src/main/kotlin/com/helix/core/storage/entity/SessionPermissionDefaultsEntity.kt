package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single-row app default session permission mode (HXA-209, ADR-PERMISSIONS-001). The one
 * row has the fixed id [DEFAULTS_ROW_ID]; a missing row (fresh install before the first write)
 * means the compiled default [com.helix.core.model.SessionPermissionMode.READ_ONLY].
 *
 * [mode] is a PRESET mode name only — the app default is never CUSTOM (CUSTOM requires an
 * explicit per-session snapshot). [configVersion] records the rule-set contract version the
 * row was written with. No [rulesJson]: a preset default's rule table is exactly its
 * `presetRules(mode)` table.
 */
@Entity(tableName = "session_permission_defaults")
data class SessionPermissionDefaultsEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val configVersion: Int,
    val revision: Long,
    val updatedAtEpoch: Long,
) {
    companion object {
        /** The fixed row id of the single app-defaults row. */
        const val DEFAULTS_ROW_ID = "app"
    }
}

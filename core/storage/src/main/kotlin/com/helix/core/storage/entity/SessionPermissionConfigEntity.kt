package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * The stored session permission configuration of ONE session (HXA-209,
 * ADR-PERMISSIONS-001 section 2 step 4): the single compiled
 * [com.helix.core.policy.SessionPermissionConfig] shape — a [mode] plus the materialized rule
 * table in [rulesJson]. Presets are stored as their FIXED rule snapshot (the preset's own
 * table); CUSTOM as the user's explicit copied-then-edited snapshot. Session creation and
 * v23 migration materialize the default; future app-default edits never change this row.
 *
 * [rulesJson] is the deterministic flat object produced by
 * [com.helix.core.storage.repository.SessionPermissionRulesCodec]. [configVersion] is the
 * rule-set contract version the row was written with; [revision] linearizes config changes
 * against execution starts (ADR section 5).
 */
@Entity(
    tableName = "session_permission_configs",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionPermissionConfigEntity(
    @PrimaryKey val sessionId: String,
    val mode: String,
    val rulesJson: String,
    val configVersion: Int,
    val revision: Long,
    val createdAtEpoch: Long,
    val updatedAtEpoch: Long,
)

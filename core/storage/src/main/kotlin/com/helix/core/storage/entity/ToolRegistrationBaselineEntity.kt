package com.helix.core.storage.entity

import androidx.room.Entity

/**
 * One trusted tool-registration/upgrade baseline marker (HXA-200, ADR-0052 point 1; 2026-09-15
 * mechanism addendum). One row per trusted tool identity — the SAME `(sourceRef, toolName)` key a
 * [ToolApprovalPreferenceEntity] uses (the origin's canonical form + the stable tool name), so a
 * baseline marker and a user preference for one tool always line up on the same identity.
 *
 * [firstSeenVersionCode] is the app versionCode at which the trusted registration path FIRST saw
 * this tool. It is written first-write-wins ([androidx.room.OnConflictStrategy.IGNORE]): a tool that
 * already has a row keeps its original first-seen version across restarts and upgrades, so the value
 * is stable. The "is this tool NEW in the current build?" decision is made at read time by comparing
 * this against the current versionCode and the founding anchor (see
 * [com.helix.core.policy.ToolBaseline]) — it is never stored as a boolean, so it can never drift.
 *
 * The table is additive and EMPTY on upgrade (migration 17 -> 18): no rows are seeded, so an
 * unconfigured, un-upgraded user has no baseline and every tool stays UNSET (the original behavior).
 * Only the trusted app registration path inserts rows here; the model, Skill, MCP, A2A and the UI
 * cannot (ADR-0052 point 1).
 */
@Entity(
    tableName = "tool_registration_baseline",
    primaryKeys = ["sourceRef", "toolName"],
)
data class ToolRegistrationBaselineEntity(
    val sourceRef: String,
    val toolName: String,
    val firstSeenVersionCode: Long,
    val updatedAtEpoch: Long,
)

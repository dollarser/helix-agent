package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durable user tool-approval preference (HXA-200, ADR-0052). This is the *preference* layer,
 * deliberately distinct from the per-call `approvals` table: a preference is a standing user
 * setting for a tool, written only by the user application service — never by the model, Skill,
 * MCP or A2A.
 *
 * Every column is a primitive so Room needs no type converters:
 *
 * - [sourceRef] + [toolName] is the stable tool identity (the origin's canonical form + the
 *   model-visible name). A collision-prone display name never identifies a tool (ADR-0052 point
 *   5); the app service computes [sourceRef] from the live `ToolDescriptor.origin`.
 * - [preference] is the [com.helix.core.model.ToolApprovalPreference] name (ALLOW/ASK/DENY).
 * - [scopeKind] is the [com.helix.core.model.ToolApprovalPreferenceScope] name; [scopeRef] is
 *   empty for GLOBAL and the session id (SESSION) or workspace `directoryRef` (WORKSPACE)
 *   otherwise. The UNIQUE [index] forbids two rows for the same identity + scope, so "reset to
 *   default" is a delete, not a fourth state (ADR-0052 point 5).
 * - [contractHash] is the descriptor `contractHash` an ALLOW was bound to (empty for ASK/DENY); a
 *   later contract change makes the stored ALLOW fall back to ASK at read time (point 6).
 * - [revision] is a monotonic counter for the linearization point with execution start (point 7).
 *
 * The table is additive and empty on upgrade (migration 16 -> 17): no ALLOW rows are seeded, so
 * an unconfigured user keeps their original behavior (point 1).
 */
@Entity(
    tableName = "tool_approval_preferences",
    indices =
        [
            Index(
                name = "index_tool_approval_preferences_key",
                unique = true,
                value = ["sourceRef", "toolName", "scopeKind", "scopeRef"],
            ),
        ],
)
data class ToolApprovalPreferenceEntity(
    @PrimaryKey val id: String,
    val sourceRef: String,
    val toolName: String,
    val preference: String,
    val scopeKind: String,
    val scopeRef: String,
    val contractHash: String,
    val revision: Long,
    val createdAtEpoch: Long,
    val updatedAtEpoch: Long,
)

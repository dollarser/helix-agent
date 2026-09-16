package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * The stored availability state of ONE tool identity in ONE scope (HXA-209,
 * ADR-PERMISSIONS-001 section 1.1). Two states only (ENABLED/DISABLED in [state]); the scope is
 * part of the row identity, so "reset to default" is a row delete.
 *
 * - [sourceRef] + [toolName] is the stable tool identity (same contract as
 *   `tool_approval_preferences` — the origin's canonical form + the model-visible name; a
 *   collision-prone display name never identifies a tool).
 * - [scopeKind] is the [com.helix.core.model.ToolAvailabilityScope] name; [scopeRef] is empty
 *   for GLOBAL, the workspace `directoryRef` for WORKSPACE, the session id for SESSION.
 * - The composite PRIMARY KEY forbids two rows for the same identity + scope.
 *
 * The migration converts old DENY preference rows (and only DENY) into DISABLED rows; ASK/ALLOW
 * rows carry no availability state and are not converted.
 */
@Entity(
    tableName = "tool_availability",
    primaryKeys = ["sourceRef", "toolName", "scopeKind", "scopeRef"],
    indices =
        [
            Index(
                name = "index_tool_availability_tool",
                value = ["sourceRef", "toolName"],
            ),
        ],
)
data class ToolAvailabilityEntity(
    val sourceRef: String,
    val toolName: String,
    val scopeKind: String,
    val scopeRef: String,
    val state: String,
    val revision: Long,
    val createdAtEpoch: Long,
    val updatedAtEpoch: Long,
)

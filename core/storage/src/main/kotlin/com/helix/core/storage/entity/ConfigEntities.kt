package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** architecture doc 9.1: `audit_events` — append-only, redacted payload only. */
@Entity(tableName = "audit_events", indices = [Index("correlationId"), Index("timestamp")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val correlationId: String,
    val type: String,
    val actor: String,
    val redactedPayload: String,
    val timestamp: Long,
)

/**
 * architecture doc 9.1: `provider_configs` — the table stores `secretAlias` only; no plaintext
 * key or token column exists in the schema (architecture doc 9.1 / 07-security).
 */
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val protocol: String,
    val endpoint: String,
    val model: String,
    val headersJson: String,
    val secretAlias: String,
    val capabilitySnapshot: String,
)

/** architecture doc 9.1: `runtime_installs` — type, version, state, manifestHash, installedAt. */
@Entity(tableName = "runtime_installs")
data class RuntimeInstallEntity(
    @PrimaryKey val id: String,
    val type: String,
    val version: String,
    val state: String,
    val manifestHash: String,
    val installedAt: Long,
)

/** architecture doc 9.1: `execution_targets` — descriptor + capabilitySnapshot. */
@Entity(tableName = "execution_targets")
data class ExecutionTargetEntity(
    @PrimaryKey val id: String,
    val type: String,
    val descriptor: String,
    val capabilitySnapshot: String,
)

/** architecture doc 9.1: `capability_grants` — type, systemState, userScopeRef, checkedAt. */
@Entity(tableName = "capability_grants", indices = [Index("type")])
data class CapabilityGrantEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long,
    val type: String,
    val systemState: String,
    val userScopeRef: String,
    val checkedAt: Long,
)

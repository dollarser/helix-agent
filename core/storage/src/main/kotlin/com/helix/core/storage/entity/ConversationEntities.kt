package com.helix.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * architecture doc 9.1: `sessions` — conversation root; archive, do not delete.
 * `providerId` is a foreign key (SET NULL): deleting a provider config (M2, HXA-020) must not
 * orphan sessions, which are archived, never deleted.
 */
@Entity(
    tableName = "sessions",
    foreignKeys =
        [
            ForeignKey(
                entity = ProviderConfigEntity::class,
                parentColumns = ["id"],
                childColumns = ["providerId"],
                onDelete = ForeignKey.SET_NULL,
            ),
        ],
    indices = [Index("providerId")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String?,
    val modelId: String?,
    val createdAt: Long,
    val archivedAt: Long?,
)

/** architecture doc 9.1: `messages` — timeline row; large content in files via `contentRef`. */
@Entity(
    tableName = "messages",
    foreignKeys =
        [
            ForeignKey(
                entity = SessionEntity::class,
                parentColumns = ["id"],
                childColumns = ["sessionId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = TurnEntity::class,
                parentColumns = ["id"],
                childColumns = ["turnId"],
                onDelete = ForeignKey.SET_NULL,
            ),
        ],
    indices = [Index(value = ["sessionId", "sequence"], unique = true), Index("turnId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val turnId: String?,
    val role: String,
    val kind: String,
    val contentRef: String?,
    val sequence: Long,
)

/** architecture doc 9.1: `turns` — one model/tool step chain. */
@Entity(
    tableName = "turns",
    foreignKeys =
        [
            ForeignKey(
                entity = SessionEntity::class,
                parentColumns = ["id"],
                childColumns = ["sessionId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("sessionId")],
)
data class TurnEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val state: String,
    val stepCount: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val errorCode: String?,
)

/** architecture doc 9.1: `model_calls` — provider snapshot, state, usage, requestId. */
@Entity(
    tableName = "model_calls",
    foreignKeys =
        [
            ForeignKey(
                entity = TurnEntity::class,
                parentColumns = ["id"],
                childColumns = ["turnId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("turnId")],
)
data class ModelCallEntity(
    @PrimaryKey val id: String,
    val turnId: String,
    val providerSnapshot: String,
    val state: String,
    val usage: String?,
    val requestId: String?,
)

/** architecture doc 9.1: `tool_calls` — canonical argsJson + immutable argsHash. */
@Entity(
    tableName = "tool_calls",
    foreignKeys =
        [
            ForeignKey(
                entity = TurnEntity::class,
                parentColumns = ["id"],
                childColumns = ["turnId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["turnId", "callId"], unique = true)],
)
data class ToolCallEntity(
    @PrimaryKey val id: String,
    val turnId: String,
    val callId: String,
    val name: String,
    val version: String,
    val argsJson: String,
    val argsHash: String,
    val state: String,
)

/** architecture doc 9.1: `tool_results` — one result per tool call, verified flag. */
@Entity(
    tableName = "tool_results",
    foreignKeys =
        [
            ForeignKey(
                entity = ToolCallEntity::class,
                parentColumns = ["id"],
                childColumns = ["toolCallId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("toolCallId", unique = true)],
)
data class ToolResultEntity(
    @PrimaryKey val id: String,
    val toolCallId: String,
    val status: String,
    val summary: String,
    val contentRef: String?,
    val verified: Boolean,
)

/**
 * architecture doc 9.1: `approvals` — one-time, per ToolCall, with decision audit.
 * `bindingHash` (v2, HXA-034; renamed from `argsHash`) is the full ApprovalBinding hash —
 * tool/version/schema/scope/session/target/UI token/args — not just the argument digest.
 * `expiresAt` (v2) bounds the approval window; migrated v1 rows default to 0 = already
 * expired (fail closed). Only `APPROVED` records can mint/consume a proof (doc 9.2).
 */
@Entity(
    tableName = "approvals",
    foreignKeys =
        [
            ForeignKey(
                entity = ToolCallEntity::class,
                parentColumns = ["id"],
                childColumns = ["toolCallId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("toolCallId", unique = true)],
)
data class ApprovalEntity(
    @PrimaryKey val id: String,
    val toolCallId: String,
    val bindingHash: String,
    val decision: String?,
    val decidedAt: Long?,
    val consumedAt: Long?,
    val expiresAt: Long,
)

/**
 * architecture doc 9.1: `executions` — runtime, limits, exit code / signal. One row per tool
 * call (same per-call convention as `approvals`/`tool_results`): `byToolCall` returns a single
 * row, so duplicates are rejected at the schema level.
 */
@Entity(
    tableName = "executions",
    foreignKeys =
        [
            ForeignKey(
                entity = ToolCallEntity::class,
                parentColumns = ["id"],
                childColumns = ["toolCallId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("toolCallId", unique = true)],
)
data class ExecutionEntity(
    @PrimaryKey val id: String,
    val toolCallId: String,
    val runtime: String,
    val limitsJson: String,
    val exitCode: Int?,
    val signal: String?,
)

/** architecture doc 9.1: `artifacts` — file + hash first, then this row. */
@Entity(
    tableName = "artifacts",
    foreignKeys =
        [
            ForeignKey(
                entity = SessionEntity::class,
                parentColumns = ["id"],
                childColumns = ["sessionId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["sessionId", "relativePath"], unique = true)],
)
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val relativePath: String,
    val mediaType: String,
    val size: Long,
    val sha256: String,
)

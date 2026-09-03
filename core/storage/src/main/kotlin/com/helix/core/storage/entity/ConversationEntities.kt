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

/**
 * architecture doc 9.1 + ADR-0014 (HXA-049): `message_attachments` — the ordered relation from a
 * message to the immutable Artifact snapshot it was bound to. `boundSha256` is the hash captured
 * at bind time; the explicit send, its disclosure confirmation and a retry of the bound turn
 * re-verify the artifact against it and fail closed on any change or a missing file (the persisted
 * inlined snapshot is re-sent verbatim on retry; a plain session re-open does NOT re-verify). The
 * body / binary stays in the file (via the artifact), never in Room. The
 * closed classification is re-derived from the hash-verified bytes at materialization, so it is not
 * a column here; `purpose` is the attachment's role in the message.
 */
@Entity(
    tableName = "message_attachments",
    foreignKeys =
        [
            ForeignKey(
                entity = MessageEntity::class,
                parentColumns = ["id"],
                childColumns = ["messageId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = ArtifactEntity::class,
                parentColumns = ["id"],
                childColumns = ["artifactId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["messageId", "ordinal"], unique = true), Index("artifactId")],
)
data class MessageAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long,
    val messageId: String,
    val artifactId: String,
    val ordinal: Int,
    val purpose: String,
    val boundSha256: String,
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
 * doc 11 section 4 (roadmap HXA-037): `interaction_receipts` — structured user questions
 * with one-time receipts. A question is bound to session/turn/requestId/version/expiry;
 * the answer is consumed EXACTLY ONCE (state guard in SQL). A receipt is deliberately NOT
 * an approval proof: there is no binding hash here, no mint path, and no receipt operation
 * ever touches the `approvals` table — a user answer can never substitute an Approval
 * Proof (type-level and table-level separation). [questionSummary] is a bounded redacted
 * summary (<=512 chars, no sensitive body); [answerHash] stores the SHA-256 of the answer
 * (the answer BODY is owned by the conversation message, not this table).
 */
@Entity(tableName = "interaction_receipts")
data class InteractionReceiptEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val turnId: String,
    val requestId: String,
    val version: Int,
    val questionSummary: String,
    val state: String,
    val createdAt: Long,
    val expiresAt: Long,
    val answerHash: String?,
    val answeredAt: Long?,
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

package com.helix.core.storage.repository

import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalMintOutcome
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.MintRejectionCode
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.ApprovalDao
import com.helix.core.storage.dao.ArtifactDao
import com.helix.core.storage.dao.AuditEventDao
import com.helix.core.storage.dao.ExecutionDao
import com.helix.core.storage.dao.MessageDao
import com.helix.core.storage.dao.ModelCallDao
import com.helix.core.storage.dao.SessionDao
import com.helix.core.storage.dao.ToolCallDao
import com.helix.core.storage.dao.ToolResultDao
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.ApprovalEntity
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.storage.entity.AuditEventEntity
import com.helix.core.storage.entity.ExecutionEntity
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.ModelCallEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.mapping.turnStateName
import java.io.File

class SessionRepository(
    private val dao: SessionDao,
) {
    fun create(
        id: String,
        title: String,
        providerId: String?,
        modelId: String?,
        createdAt: Long,
    ): SessionEntity {
        require(title.isNotBlank()) { "session title must not be blank" }
        require(createdAt >= 0) { "createdAt must be >= 0" }
        val entity = SessionEntity(id, title, providerId, modelId, createdAt, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): SessionEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("session not found: $id")
    }

    fun list(): List<SessionEntity> = dao.list()

    fun archive(
        id: String,
        archivedAt: Long,
    ) {
        require(archivedAt >= 0) { "archivedAt must be >= 0" }
        val updated = dao.archive(id, archivedAt)
        require(updated == 1) { "session not archivable: $id" }
    }
    // No delete: sessions are archived, never deleted (doc 9.1 / entity contract). A hard
    // delete would cascade the session's approvals/executions audit rows, which must be
    // durable (AGENTS.md: every tool call goes through audit). A retention wipe, if ever
    // authorized, is a future HXA decision with its own review.
}

class MessageRepository(
    private val dao: MessageDao,
    private val contentStore: ContentStore,
) {
    /**
     * Appends a message; [content] (when non-blank) is stored in [contentStore] and only the
     * reference is kept in Room (doc 9.2). Sequence allocation must run inside
     * [com.helix.core.storage.HelixStorage.withTransaction] when concurrent.
     */
    fun append(
        id: String,
        sessionId: String,
        turnId: String?,
        role: String,
        kind: String,
        content: String,
    ): MessageEntity {
        require(role.isNotBlank()) { "role must not be blank" }
        require(kind.isNotBlank()) { "kind must not be blank" }
        // maxSequence is COALESCE(MAX(sequence), -1), so max + 1 is always a valid sequence.
        val sequence = dao.maxSequence(sessionId) + 1
        val contentRef =
            if (content.isBlank()) {
                null
            } else {
                contentStore.write(content).toStorageString()
            }
        val entity = MessageEntity(id, sessionId, turnId, role, kind, contentRef, sequence)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): MessageEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("message not found: $id")
    }

    fun listBySession(sessionId: String): List<MessageEntity> = dao.listBySession(sessionId)

    fun readContent(message: MessageEntity): String? {
        val ref = message.contentRef ?: return null
        return contentStore.read(ContentRef.parse(ref))
    }
}

class TurnRepository(
    private val dao: TurnDao,
) {
    fun start(
        id: String,
        sessionId: String,
        startedAt: Long,
    ): TurnEntity {
        require(startedAt >= 0) { "startedAt must be >= 0" }
        val entity = TurnEntity(id, sessionId, TurnState.CREATED.name, 0, startedAt, null, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): TurnEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("turn not found: $id")
    }

    fun listBySession(sessionId: String): List<TurnEntity> = dao.listBySession(sessionId)

    /** Non-terminal turns left by a previous process — the HXA-015 recovery scan. */
    fun listActive(): List<TurnEntity> = dao.listActive()

    /** [TurnState] name is validated before it hits the column. */
    fun updateState(
        turn: TurnEntity,
        state: TurnState,
        stepCount: Int,
        endedAt: Long?,
        errorCode: String?,
    ): TurnEntity {
        require(stepCount >= turn.stepCount) { "stepCount must never decrease" }
        endedAt?.let { require(it >= turn.startedAt) { "endedAt must be >= startedAt" } }
        dao.updateState(turn.id, state.name, stepCount, endedAt, errorCode)
        return turn.copy(state = state.name, stepCount = stepCount, endedAt = endedAt, errorCode = errorCode)
    }
}

class ModelCallRepository(
    private val dao: ModelCallDao,
) {
    fun append(
        id: String,
        turnId: String,
        providerSnapshot: String,
        state: String,
    ): ModelCallEntity {
        require(providerSnapshot.isNotBlank()) { "providerSnapshot must not be blank" }
        require(state.isNotBlank()) { "state must not be blank" }
        val entity = ModelCallEntity(id, turnId, providerSnapshot, state, null, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ModelCallEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("model call not found: $id")
    }

    fun listByTurn(turnId: String): List<ModelCallEntity> = dao.listByTurn(turnId)

    fun update(
        call: ModelCallEntity,
        state: String,
        usage: String?,
        requestId: String?,
    ) {
        require(state.isNotBlank()) { "state must not be blank" }
        dao.update(call.id, state, usage, requestId)
    }
}

class ToolCallRepository(
    private val dao: ToolCallDao,
) {
    /**
     * Registers a tool call. `argsJson` must already be canonical (doc 9.2); [argsHash] is
     * computed here so the hash and the stored body cannot drift.
     */
    fun append(
        id: String,
        turnId: String,
        callId: String,
        name: String,
        version: String,
        argsJson: String,
        state: String,
    ): ToolCallEntity {
        require(callId.isNotBlank()) { "callId must not be blank" }
        require(name.isNotBlank()) { "tool name must not be blank" }
        require(version.isNotBlank()) { "tool version must not be blank" }
        require(state.isNotBlank()) { "state must not be blank" }
        val entity =
            ToolCallEntity(
                id,
                turnId,
                callId,
                name,
                version,
                argsJson,
                FileContentStore.sha256Hex(argsJson.toByteArray(Charsets.UTF_8)),
                state,
            )
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ToolCallEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("tool call not found: $id")
    }

    fun listByTurn(turnId: String): List<ToolCallEntity> = dao.listByTurn(turnId)

    fun byTurnAndCallId(
        turnId: String,
        callId: String,
    ): ToolCallEntity? = dao.byTurnAndCallId(turnId, callId)

    /**
     * Updates a tool call to [state]. The [ToolCallState] is validated before it hits the column
     * (HXA-015 recovery parks in-flight calls in [ToolCallState.INTERRUPTED]; a parked state must
     * never silently degrade to an arbitrary string).
     */
    fun updateState(
        call: ToolCallEntity,
        state: ToolCallState,
    ) {
        dao.updateState(call.id, state.name)
    }
}

class ToolResultRepository(
    private val dao: ToolResultDao,
    private val contentStore: ContentStore,
) {
    fun append(
        id: String,
        toolCallId: String,
        status: String,
        summary: String,
        content: String?,
    ): ToolResultEntity {
        require(status.isNotBlank()) { "status must not be blank" }
        require(summary.isNotBlank()) { "summary must not be blank" }
        val contentRef =
            if (content == null || content.isBlank()) {
                null
            } else {
                contentStore.write(content).toStorageString()
            }
        val entity = ToolResultEntity(id, toolCallId, status, summary, contentRef, false)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ToolResultEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("tool result not found: $id")
    }

    fun byToolCall(toolCallId: String): ToolResultEntity? = dao.byToolCall(toolCallId)

    fun markVerified(result: ToolResultEntity) {
        require(!result.verified) { "tool result already verified: ${result.id}" }
        require(dao.markVerified(result.id) == 1) { "tool result could not be verified: ${result.id}" }
    }

    fun readContent(result: ToolResultEntity): String? {
        val ref = result.contentRef ?: return null
        return contentStore.read(ContentRef.parse(ref))
    }
}

/**
 * Approval records with one-time, binding-checked proof consumption (HXA-034).
 *
 * The repository API only accepts the closed [ApprovalDecision] set — free strings cannot
 * enter (the DAO re-checks `IN ('APPROVED', 'DENIED')` in SQL as a second layer). Minting a
 * typed [ApprovalProof] happens only for APPROVED, unexpired, unconsumed records; consuming
 * verifies the proof's binding hash inside the atomic UPDATE, so pending/DENIED/expired
 * records and forged hashes can never authorize execution. `decision != null` and
 * `consumedAt != null` are record-processing facts and are never approval by themselves
 * (architecture doc 9.2; security doc 7.3).
 */
class ApprovalRepository(
    private val dao: ApprovalDao,
) {
    /**
     * Creates a pending approval bound to the exact [binding]. The window is
     * `createdAt < expiresAt` with a hard [MAX_APPROVAL_TTL_MILLIS] cap: an approval is
     * per-exact-ToolCall and finite — there is no permanent or unbounded approval.
     */
    fun create(
        id: String,
        toolCallId: String,
        binding: ApprovalBinding,
        createdAt: Long,
        expiresAt: Long,
    ): ApprovalEntity {
        require(createdAt >= 0) { "createdAt must be >= 0" }
        require(expiresAt > createdAt) { "expiresAt must be after createdAt" }
        require(expiresAt - createdAt <= MAX_APPROVAL_TTL_MILLIS) {
            "approval window exceeds the $MAX_APPROVAL_TTL_MILLIS ms hard cap"
        }
        val entity = ApprovalEntity(id, toolCallId, binding.hash, null, null, null, expiresAt)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ApprovalEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("approval not found: $id")
    }

    fun byToolCall(toolCallId: String): ApprovalEntity? = dao.byToolCall(toolCallId)

    /** One-time: throws when a decision already exists. */
    fun decide(
        id: String,
        decision: ApprovalDecision,
        decidedAt: Long,
    ): ApprovalEntity {
        require(decidedAt >= 0) { "decidedAt must be >= 0" }
        require(dao.decide(id, decision.name, decidedAt) == 1) { "approval already decided: $id" }
        return resolve(id)
    }

    /**
     * Mints the typed proof for an approval — the ONLY path to an [ApprovalProof]. Pending,
     * DENIED, expired and already-consumed records are rejected with a stable code (never
     * minted, even if their decision/consumed fields are non-null).
     */
    fun mint(
        id: String,
        now: Long,
    ): ApprovalMintOutcome {
        require(now >= 0) { "now must be >= 0" }
        val entity = dao.byId(id) ?: return ApprovalMintOutcome.Rejected(MintRejectionCode.NOT_FOUND)
        return when {
            entity.decision == null -> {
                ApprovalMintOutcome.Rejected(MintRejectionCode.PENDING)
            }

            entity.decision == ApprovalDecision.DENIED.name -> {
                ApprovalMintOutcome.Rejected(MintRejectionCode.DENIED)
            }

            entity.consumedAt != null -> {
                ApprovalMintOutcome.Rejected(MintRejectionCode.CONSUMED)
            }

            now >= entity.expiresAt -> {
                ApprovalMintOutcome.Rejected(MintRejectionCode.EXPIRED)
            }

            else -> {
                ApprovalMintOutcome.Minted(ApprovalProof(id, entity.bindingHash))
            }
        }
    }

    /**
     * One-time consumption of a minted proof. The atomic guard re-checks in SQL: APPROVED,
     * not consumed, not expired at [now], and stored binding hash equal to the proof's.
     * Throws when the record is pending, DENIED, expired, consumed or hash-mismatched — and
     * under concurrency exactly one consumer wins (affected-row-count guard).
     */
    fun consume(
        proof: ApprovalProof,
        consumedAt: Long,
        now: Long,
    ): ApprovalEntity {
        require(consumedAt >= 0) { "consumedAt must be >= 0" }
        require(now >= 0) { "now must be >= 0" }
        require(
            dao.consumeByBinding(proof.approvalId, proof.bindingHash, consumedAt, now) == 1,
        ) { "approval not consumable: ${proof.approvalId}" }
        return resolve(proof.approvalId)
    }

    companion object {
        /** Product hard cap for an approval window (24 h): per-exact-ToolCall, finite, no permanent approval. */
        const val MAX_APPROVAL_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}

class ExecutionRepository(
    private val dao: ExecutionDao,
) {
    fun register(
        id: String,
        toolCallId: String,
        runtime: String,
        limitsJson: String,
    ): ExecutionEntity {
        require(runtime.isNotBlank()) { "runtime must not be blank" }
        require(limitsJson.isNotBlank()) { "limitsJson must not be blank" }
        val entity = ExecutionEntity(id, toolCallId, runtime, limitsJson, null, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ExecutionEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("execution not found: $id")
    }

    fun byToolCall(toolCallId: String): ExecutionEntity? = dao.byToolCall(toolCallId)

    fun updateOutcome(
        execution: ExecutionEntity,
        exitCode: Int?,
        signal: String?,
    ) {
        require(exitCode != null || signal != null) { "an outcome needs an exit code or a signal" }
        dao.updateOutcome(execution.id, exitCode, signal)
    }
}

class ArtifactRepository(
    private val dao: ArtifactDao,
) {
    /**
     * Registers an artifact. doc 9.2: the file with its hash must exist first — [file] is
     * always re-verified (existence, size, SHA-256) before the row lands. There is no
     * out-of-band path: an unverified reference row is exactly what this guard exists to
     * prevent.
     */
    fun register(
        id: String,
        sessionId: String,
        relativePath: String,
        mediaType: String,
        size: Long,
        sha256: String,
        file: File,
    ): ArtifactEntity {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/")) {
            "relativePath must be a non-blank relative path"
        }
        require(mediaType.isNotBlank()) { "mediaType must not be blank" }
        require(size >= 0) { "size must be >= 0" }
        require(sha256.length == 64) { "sha256 must be a hex string" }
        require(file.isFile) { "artifact file not found: $relativePath" }
        require(file.length() == size) { "artifact file size mismatch for $relativePath" }
        require(FileContentStore.sha256Hex(file.readBytes()) == sha256) {
            "artifact file hash mismatch for $relativePath"
        }
        val entity = ArtifactEntity(id, sessionId, relativePath, mediaType, size, sha256)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ArtifactEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("artifact not found: $id")
    }

    fun listBySession(sessionId: String): List<ArtifactEntity> = dao.listBySession(sessionId)
}

class AuditEventRepository(
    private val dao: AuditEventDao,
) {
    fun append(
        id: String,
        correlationId: String,
        type: String,
        actor: String,
        redactedPayload: String,
        timestamp: Long,
    ): AuditEventEntity {
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
        require(type.isNotBlank()) { "event type must not be blank" }
        require(actor.isNotBlank()) { "actor must not be blank" }
        require(timestamp >= 0) { "timestamp must be >= 0" }
        val entity = AuditEventEntity(id, correlationId, type, actor, redactedPayload, timestamp)
        dao.append(entity)
        return entity
    }

    fun resolve(id: String): AuditEventEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("audit event not found: $id")
    }

    fun listByCorrelation(correlationId: String): List<AuditEventEntity> = dao.listByCorrelation(correlationId)
}

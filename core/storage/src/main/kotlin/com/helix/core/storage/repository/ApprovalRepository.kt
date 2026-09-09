package com.helix.core.storage.repository

import com.helix.core.model.ApprovalDecision
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalMintOutcome
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.MintRejectionCode
import com.helix.core.storage.dao.ApprovalDao
import com.helix.core.storage.entity.ApprovalEntity

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

    /**
     * One-time refund of a consumed proof — the storage half of a bounded technical retry
     * (roadmap HXA-037; doc 11 section 3.3). The dispatcher has already CONFIRMED the
     * failed attempt was zero-side-effect, so the consumption is annulled and the SAME
     * typed APPROVED record may be re-minted once. Returns false (and changes nothing)
     * when the record is not a currently-consumed APPROVED for this exact binding — a
     * second refund is structurally impossible (SQL guard). The refund never writes a
     * decision and never extends the record's window.
     */
    fun refund(proof: ApprovalProof): Boolean = dao.refundByBinding(proof.approvalId, proof.bindingHash) == 1

    companion object {
        /** Product hard cap for an approval window (24 h): per-exact-ToolCall, finite, no permanent approval. */
        const val MAX_APPROVAL_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}

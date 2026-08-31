package com.helix.tools.framework

import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.MintRejectionCode

/**
 * Everything the dispatcher needs to know to obtain (and later spend) a typed Approval
 * Proof for one call (roadmap HXA-035; security doc section 7.3).
 *
 * The dispatcher NEVER stores, inspects or forges proofs itself: it hands the full
 * [ApprovalBinding] (built exclusively from trusted execution-path facts) to the broker and
 * accepts only a typed [ApprovalAcquisition]. The production broker (HXA-036) implements
 * this against the storage layer's closed `ApprovalDecision` set, the approval UI and the
 * atomic HXA-034 mint/consume guards; tests use scripted fakes.
 *
 * Invariants the dispatcher relies on (enforced by the HXA-034 storage layer, not by this
 * port): only a typed `APPROVED` + unexpired + unconsumed record can ever produce
 * [ApprovalAcquisition.Approved]; `DENIED` and every [MintRejectionCode] come back as
 * non-credentials; a proof can be consumed exactly once.
 */
interface ApprovalBroker {
    /**
     * Acquires a one-time approval for [request.binding]: creates the pending record if
     * needed, presents the confirmation surface (bound to the binding's UI token) and mints
     * a proof only from a typed APPROVED record. [request.confirmationDetail] is the
     * human-readable confirmation surface from the policy decision (data category +
     * Provider/MCP + origin + scope for egress, ADR-0005).
     */
    fun acquire(request: ApprovalRequest): ApprovalAcquisition

    /**
     * Consumes [proof] exactly once. The dispatcher calls this exactly when execution
     * STARTS — the authorization is spent the moment the effect begins; calls that never
     * started (cancelled before start) leave the proof unconsumed.
     */
    fun consume(proof: ApprovalProof)
}

/** One acquisition attempt: the exact binding + the policy decision's confirmation detail. */
data class ApprovalRequest(
    val binding: ApprovalBinding,
    val confirmationDetail: String,
)

/** The broker's typed answer; only [Approved] is a credential. */
sealed interface ApprovalAcquisition {
    /** A one-time proof for the exact binding; executable exactly once. */
    data class Approved(
        val proof: ApprovalProof,
    ) : ApprovalAcquisition

    /**
     * No credential: the record exists but is not (yet / no longer) consumable — pending,
     * expired, already consumed, or unknown. [code] is stable for UI and audit.
     */
    data class Rejected(
        val code: MintRejectionCode,
    ) : ApprovalAcquisition

    /** The user decision is DENIED: a processed decision, never a credential (HXA-034). */
    data object Denied : ApprovalAcquisition
}

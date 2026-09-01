package com.helix.tools.framework

import com.helix.core.model.RiskLevel
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.HighSensitivityRule
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

/**
 * One acquisition attempt: the exact binding + the policy decision's confirmation detail +
 * the Policy Engine's DYNAMIC risk for this call (base risk plus egress/change factors).
 *
 * [dynamicRisk] is what the approval card (HXA-036) must show as the call's risk — the
 * value the policy engine actually used, not the descriptor's base risk alone.
 *
 * [cancel] is the dispatch's turn-level [CancelSignal] (roadmap HXA-036): the broker's
 * blocking wait for the user's decision must observe it so a turn stop CANCELS the wait
 * even when the confirmation surface (and the broker's own card-level cancel hook) has
 * not registered the pending card yet — a stopped turn must never keep waiting, and never
 * execute, after the stop. The signal never grants anything: it can only end the wait in
 * the cancelled (non-credential) direction.
 *
 * [boundedEgressRule] is the LIVE, exactly-bound ADVANCED rule that already satisfied this
 * call's high-sensitivity egress (roadmap HXA-036: 高敏出网规则单独标为有界 Policy 规则).
 * It is DISPLAY-ONLY metadata: the card must render it as a bounded rule with its window,
 * never as a general approval credential. It does not participate in the [binding] hash
 * and grants nothing — the egress it covers was already released by the Policy Engine.
 */
data class ApprovalRequest(
    val binding: ApprovalBinding,
    val confirmationDetail: String,
    val dynamicRisk: RiskLevel,
    val cancel: CancelSignal = NoCancellation,
    val boundedEgressRule: HighSensitivityRule? = null,
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

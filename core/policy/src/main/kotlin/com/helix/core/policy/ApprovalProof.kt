package com.helix.core.policy

/**
 * A typed approval credential for exactly one ToolCall (roadmap HXA-034).
 *
 * A proof is minted ONLY from an approval record that is APPROVED, not expired and not yet
 * consumed; it carries the stored [ApprovalBinding.hash], so consuming it re-verifies the full
 * binding (tool/version/schema/scope/session/target/UI token/args) inside the atomic consume
 * guard. `DENIED` records never mint, even after being decided/viewed; `PENDING` never mints;
 * an expired record never mints. Nothing in the API lets a caller treat `decision != null` or
 * `consumedAt != null` as approval — those are record-processing facts, not credentials
 * (architecture doc section 9.2; security doc section 7.3).
 *
 * A proof is per-ToolCall and one-time: it is not an Approval Proof for file mutation, shell,
 * Root, Accessibility or egress rules (ADR-0005 keeps those separate), and no general
 * full-access/auto-approve credential exists or may be added (AGENTS).
 */
data class ApprovalProof(
    val approvalId: String,
    val bindingHash: String,
) {
    init {
        require(approvalId.length in 1..64) { "approvalId must be 1..64 chars" }
        require(ApprovalBinding.isSha256Hex(bindingHash)) { "bindingHash must be a sha256 hex string" }
    }
}

/** Why minting a proof was rejected; stable for UI and audit. */
enum class MintRejectionCode {
    /** No approval record with that id. */
    NOT_FOUND,

    /** The record has no decision yet. */
    PENDING,

    /** The user decision is DENIED — never mintable, ever. */
    DENIED,

    /** The record is APPROVED but past its expiry window. */
    EXPIRED,

    /** The proof was already consumed (one-time). */
    CONSUMED,
}

/** Mint result: a proof, or a stable rejection. */
sealed interface ApprovalMintOutcome {
    data class Minted(
        val proof: ApprovalProof,
    ) : ApprovalMintOutcome

    data class Rejected(
        val code: MintRejectionCode,
    ) : ApprovalMintOutcome
}

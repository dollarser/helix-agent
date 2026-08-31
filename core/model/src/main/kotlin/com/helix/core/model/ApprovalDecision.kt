package com.helix.core.model

/** Closed user decision set for a tool-call approval. Only [APPROVED] is consumable. */
enum class ApprovalDecision {
    APPROVED,
    DENIED,
}

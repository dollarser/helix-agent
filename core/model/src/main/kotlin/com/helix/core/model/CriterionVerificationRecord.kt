package com.helix.core.model

/** Durable provenance, revalidated against Room by the host; these IDs alone are not proof. */
data class CriterionEvidenceSource(
    val goalId: GoalId,
    val runId: GoalRunId,
    val sessionId: SessionId,
    val turnId: TurnId,
)

/**
 * A host verification receipt for the references carried by CriterionEvidence. Loading this
 * value does not authorize completion: source state, content and binding require revalidation.
 */
data class CriterionVerificationRecord(
    val method: CriterionVerificationMethod,
    val bindingHash: Sha256,
    val source: CriterionEvidenceSource,
    val contentHash: Sha256,
    val verifiedAtEpochMillis: Long,
    val version: Int = CURRENT_VERSION,
    val sourceHash: Sha256? = null,
) {
    init {
        require(version == CURRENT_VERSION) { "unsupported criterion verification version" }
        require(verifiedAtEpochMillis >= 0) { "verification time must not be negative" }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

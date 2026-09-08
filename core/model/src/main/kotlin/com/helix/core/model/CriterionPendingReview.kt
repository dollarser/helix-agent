package com.helix.core.model

/** A user-reviewed selection awaiting explicit Continue and host revalidation; never completion evidence. */
data class CriterionPendingReview(
    val toolCallId: ToolCallId,
    val bindingHash: Sha256,
    val sourceHash: Sha256,
    val artifactRef: ArtifactRef?,
    val reviewedAtEpochMillis: Long,
    val version: Int = CURRENT_VERSION,
) {
    init {
        require(version == CURRENT_VERSION) { "unsupported pending review version" }
        require(reviewedAtEpochMillis >= 0) { "review time must not be negative" }
    }

    fun matches(
        binding: CriterionVerificationBinding?,
        id: String,
        description: String,
    ): Boolean =
        binding?.method == CriterionVerificationMethod.MANUAL_REVIEW && binding.hash(id, description) == bindingHash

    companion object {
        const val CURRENT_VERSION = 1
    }
}

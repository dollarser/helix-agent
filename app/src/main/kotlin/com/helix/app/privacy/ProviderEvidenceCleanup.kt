package com.helix.app.privacy

/** Session erasure and provider evidence reclamation are separate outcomes. No private payloads. */
data class ProviderEvidenceCleanup(
    val status: Status = Status.NOT_APPLICABLE,
    val inspected: Int = 0,
    val inspectedBytes: Long = 0,
    val deleted: Int = 0,
    val deletedBytes: Long = 0,
    val retained: Int = 0,
    val failed: Int = 0,
    val nextAfter: String? = null,
) {
    enum class Status {
        NOT_APPLICABLE,
        COMPLETE,
        MORE_AVAILABLE,
        BUSY,
        UNAVAILABLE,
        OUTCOME_UNKNOWN,
        HISTORY_UNVERIFIED,
        PARTIAL_FAILURE,
    }
}

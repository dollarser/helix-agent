package com.helix.app.provider

/** Original Runtime Job status; success does not mean its output has been imported or verified. */
enum class SubscriptionRecoveryStatus {
    UNKNOWN,
    RUNNING,
    SUCCEEDED_UNVERIFIED,
    STOPPED,
    ENDED,
    EVIDENCE_EXPIRED,
    STOP_REQUESTED,
}

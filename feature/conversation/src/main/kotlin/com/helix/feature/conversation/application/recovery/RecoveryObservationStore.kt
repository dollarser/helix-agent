package com.helix.feature.conversation.application.recovery

/** Closed, local read facts only. No executor operation or replay permission is exposed. */
interface RecoveryObservationStore {
    /** Resolve the original Turn even when its conversation is not visible. */
    fun originalTurn(turnId: String): OriginalRecoveryTargets

    fun interruptedSubscriptions(sessionId: String): List<SubscriptionRecoveryFact>
}

/** All identities come from one local snapshot; original executors revalidate their own bindings. */
data class OriginalRecoveryTargets(
    val turnId: String,
    val sessionId: String,
    val subscriptionModelCallIds: List<String>,
    /** INTERRUPTED/NEEDS_REVIEW bash, code.linux.run or skills.run calls only. */
    val unknownProotCallIds: List<String>,
)

/** A persisted interrupted Turn/ModelCall pair with an original CLI preparation record. */
data class SubscriptionRecoveryFact(
    val turnId: String,
    val modelCallId: String,
    val localResultAvailable: Boolean,
)

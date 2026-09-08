package com.helix.app.chat

import com.helix.app.provider.SubscriptionRecoveryStatus
import com.helix.core.storage.HelixStorage

/** Display facts only: discovery reads local evidence and never binds a Runtime. */
data class SubscriptionRecoveryUi(
    val turnId: String,
    val modelCallId: String,
    val busy: Boolean = false,
    val status: SubscriptionRecoveryStatus? = null,
    val output: com.helix.app.provider.SubscriptionRecoveredOutput? = null,
    val outputUnavailable: Boolean = false,
    val localResultAvailable: Boolean = false,
)

internal fun subscriptionRecoveriesFor(
    storage: HelixStorage,
    sessionId: String?,
    previous: List<SubscriptionRecoveryUi>,
): List<SubscriptionRecoveryUi> {
    if (sessionId == null) return emptyList()
    val prepared =
        storage.auditEvents
            .listByCorrelation(sessionId)
            .filter { it.type == "cli.job_prepared" }
            .map { it.id }
            .toSet()
    val artifactIds =
        storage.artifacts
            .listBySession(sessionId)
            .map { it.id }
            .toSet()
    return storage.turns.listBySession(sessionId).filter { it.state == "INTERRUPTED" }.flatMap { turn ->
        storage.modelCalls
            .listByTurn(turn.id)
            .filter { it.state == "INTERRUPTED" && "cli-job-${it.id}" in prepared }
            .map { call ->
                (previous.singleOrNull { it.modelCallId == call.id } ?: SubscriptionRecoveryUi(turn.id, call.id))
                    .copy(localResultAvailable = "cli-result-${call.id}" in artifactIds)
            }
    }
}

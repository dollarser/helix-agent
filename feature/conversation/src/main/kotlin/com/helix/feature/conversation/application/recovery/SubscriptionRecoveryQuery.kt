package com.helix.feature.conversation.application.recovery

import com.helix.feature.conversation.presentation.SubscriptionRecoveryUi

/** Discovery reads local facts and retains presentation only for the same original identity. */
fun subscriptionRecoveriesFor(
    store: RecoveryObservationStore,
    sessionId: String?,
    previous: List<SubscriptionRecoveryUi>,
): List<SubscriptionRecoveryUi> {
    if (sessionId == null) return emptyList()
    return store.interruptedSubscriptions(sessionId).map { fact ->
        (
            previous.singleOrNull { it.turnId == fact.turnId && it.modelCallId == fact.modelCallId }
                ?: SubscriptionRecoveryUi(fact.turnId, fact.modelCallId)
        ).copy(localResultAvailable = fact.localResultAvailable)
    }
}

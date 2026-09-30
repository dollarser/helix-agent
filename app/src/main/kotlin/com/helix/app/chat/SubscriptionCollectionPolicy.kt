package com.helix.app.chat

import com.helix.app.provider.SubscriptionRecoveryStatus

/** Query before fetching success-only evidence; an ended observation is not a successful task. */
internal object SubscriptionCollectionPolicy {
    fun beforeCollect(status: SubscriptionRecoveryStatus): AutomaticRuntimeCollection.Observation? =
        when (status) {
            SubscriptionRecoveryStatus.RUNNING, SubscriptionRecoveryStatus.STOP_REQUESTED -> {
                AutomaticRuntimeCollection.Observation.RUNNING
            }

            SubscriptionRecoveryStatus.UNKNOWN -> {
                AutomaticRuntimeCollection.Observation.RETRY
            }

            SubscriptionRecoveryStatus.STOPPED, SubscriptionRecoveryStatus.ENDED,
            SubscriptionRecoveryStatus.EVIDENCE_EXPIRED,
            -> {
                AutomaticRuntimeCollection.Observation.COMPLETE
            }

            SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED -> {
                null
            }
        }
}

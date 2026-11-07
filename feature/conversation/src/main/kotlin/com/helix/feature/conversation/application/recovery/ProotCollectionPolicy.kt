package com.helix.feature.conversation.application.recovery

import com.helix.feature.conversation.presentation.ProotRecoveryStatus

/** A terminal observation is not a successful job; only SUCCEEDED admits archive collection. */
internal object ProotCollectionPolicy {
    fun beforeCollect(status: ProotRecoveryStatus): AutomaticRuntimeCollection.Observation? =
        when (status) {
            ProotRecoveryStatus.RUNNING -> AutomaticRuntimeCollection.Observation.RUNNING
            ProotRecoveryStatus.UNKNOWN -> AutomaticRuntimeCollection.Observation.RETRY
            ProotRecoveryStatus.TERMINAL, ProotRecoveryStatus.EXPIRED -> AutomaticRuntimeCollection.Observation.COMPLETE
            ProotRecoveryStatus.SUCCEEDED -> null
        }
}

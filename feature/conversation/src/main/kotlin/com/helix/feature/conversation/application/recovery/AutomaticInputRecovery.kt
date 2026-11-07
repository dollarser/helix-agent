package com.helix.feature.conversation.application.recovery

import com.helix.core.model.Clock
import com.helix.core.model.input.InputRecoveryClaim
import com.helix.core.model.input.InputRecoveryStore

/** Revalidates the claimed bytes once. A suspended check cannot retarget a later input revision. */
class AutomaticInputRecovery(
    private val store: InputRecoveryStore,
    private val clock: Clock,
    private val revalidate: suspend (com.helix.core.model.input.RecoveryInputCandidate) -> Boolean,
    private val failureText: () -> String,
) {
    suspend fun recover(sessionId: String) {
        store.candidates(sessionId).forEach { candidate ->
            when (val claim = store.claim(candidate, now())) {
                InputRecoveryClaim.Unavailable -> {
                    Unit
                }

                InputRecoveryClaim.AlreadyClaimed -> {
                    store.fail(candidate, "INPUT_RECOVERY_INTERRUPTED", failureText(), now())
                }

                is InputRecoveryClaim.Claimed -> {
                    recoverClaimed(claim.input)
                }
            }
        }
    }

    private suspend fun recoverClaimed(input: com.helix.core.model.input.RecoveryInputCandidate) {
        if (revalidate(input)) {
            if (!store.resume(input, now())) {
                store.fail(input, "INPUT_RECOVERY_UNAVAILABLE", failureText(), now())
            }
        } else {
            store.fail(input, "INPUT_REVALIDATION_FAILED", failureText(), now())
        }
    }

    private fun now() = clock.now().toEpochMilli()
}

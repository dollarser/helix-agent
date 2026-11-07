package com.helix.feature.conversation.application.recovery

import com.helix.core.agent.RecoveryInspectionSnapshot
import com.helix.core.model.Clock
import kotlinx.coroutines.CancellationException

/** The existing submission owner runs one bounded original-task inspection, never executor replay. */
class AutomaticTurnRecovery(
    private val store: TurnRecoveryStore,
    private val clock: Clock,
    private val start: suspend (String, RecoveryInspectionSnapshot) -> String?,
    private val reportFailure: (Exception) -> Unit,
) {
    // A failed inspection admission closes the original task; it never replays work.
    @Suppress("TooGenericExceptionCaught")
    suspend fun recover(turnId: String) {
        when (val claim = store.claim(turnId, clock.now().toEpochMilli())) {
            TurnRecoveryClaim.Unavailable -> {
                Unit
            }

            TurnRecoveryClaim.AlreadyClaimed -> {
                store.finish(turnId, "RECOVERY_INTERRUPTED", true)
            }

            is TurnRecoveryClaim.InspectionFinished -> {
                store.finish(turnId, "RECOVERY_INSPECTION_FINISHED", claim.failed)
            }

            is TurnRecoveryClaim.Claimed -> {
                val started =
                    try {
                        claim.snapshot?.let { start(turnId, it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        reportFailure(error)
                        null
                    }
                if (started == null && !store.hasSuccessor(turnId)) {
                    store.finish(turnId, "RECOVERY_UNAVAILABLE", true)
                }
            }
        }
    }
}

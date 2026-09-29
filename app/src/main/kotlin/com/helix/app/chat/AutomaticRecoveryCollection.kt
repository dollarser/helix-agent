package com.helix.app.chat

import com.helix.app.proot.ProotRecoveredOutput
import com.helix.app.provider.SubscriptionRecoveredOutput
import com.helix.app.provider.SubscriptionRecoveryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Observation only; original executors retain execution and result ownership. */
@Suppress("LongParameterList") // Shared locks and narrow existing backend callbacks; no new execution path.
internal class AutomaticRecoveryCollection(
    scope: CoroutineScope,
    private val screenState: MutableStateFlow<ChatScreenState>,
    private val subscriptionRecoveryMutex: Mutex,
    private val subscriptionRecovery: (String, String, Boolean) -> SubscriptionRecoveryStatus,
    private val subscriptionResultRecovery: (String, String, Boolean) -> SubscriptionRecoveredOutput?,
    private val collectProot: (String, String, Boolean) -> ProotRecoveredOutput?,
    private val updateSubscriptionRecovery: (SubscriptionRecoveryUi) -> Unit,
) {
    private val automatic = AutomaticRuntimeCollection(scope)

    fun collect() {
        val screen = screenState.value
        collectSubscriptions(screen)
        collectProotResults(screen, automatic, screenState, collectProot)
    }

    private fun collectSubscriptions(screen: ChatScreenState) {
        screen.subscriptionRecoveries.forEach { row ->
            automatic.request("subscription:${row.modelCallId}") {
                subscriptionRecoveryMutex.lock()
                try {
                    val latest =
                        screenState.value.subscriptionRecoveries.singleOrNull {
                            it.turnId == row.turnId && it.modelCallId == row.modelCallId
                        } ?: return@request AutomaticRuntimeCollection.Observation.DEFERRED
                    if (latest.output != null) return@request AutomaticRuntimeCollection.Observation.COMPLETE
                    val status = subscriptionRecovery(row.turnId, row.modelCallId, false)
                    val output = subscriptionResultRecovery(row.turnId, row.modelCallId, false)
                    updateSubscriptionRecovery(
                        latest.copy(
                            status = status,
                            output = output,
                            localResultAvailable = output != null || latest.localResultAvailable,
                            outputUnavailable = output == null,
                        ),
                    )
                    when {
                        output != null -> {
                            AutomaticRuntimeCollection.Observation.COMPLETE
                        }

                        status == com.helix.app.provider.SubscriptionRecoveryStatus.RUNNING ||
                            status == com.helix.app.provider.SubscriptionRecoveryStatus.STOP_REQUESTED -> {
                            AutomaticRuntimeCollection.Observation.RUNNING
                        }

                        else -> {
                            AutomaticRuntimeCollection.Observation.RETRY
                        }
                    }
                } finally {
                    subscriptionRecoveryMutex.unlock()
                }
            }
        }
    }
}

private fun collectProotResults(
    screen: ChatScreenState,
    automatic: AutomaticRuntimeCollection,
    screenState: MutableStateFlow<ChatScreenState>,
    collectProot: (String, String, Boolean) -> com.helix.app.proot.ProotRecoveredOutput?,
) {
    screen.toolTimeline.filter { it.prootRecoveryAvailable }.forEach { row ->
        automatic.request("proot:${row.turnId}:${row.callId}") {
            val latest =
                screenState.value.toolTimeline.singleOrNull {
                    it.turnId == row.turnId && it.callId == row.callId
                } ?: return@request AutomaticRuntimeCollection.Observation.DEFERRED
            if (latest.prootRecoveredOutput?.acknowledged == true) {
                return@request AutomaticRuntimeCollection.Observation.COMPLETE
            }
            val output = collectProot(row.turnId, row.callId, false)
            screenState.update { current ->
                current.copy(
                    toolTimeline =
                        current.toolTimeline.map {
                            if (it.callId == row.callId && it.turnId == row.turnId) {
                                it.copy(
                                    prootRecoveredOutput = output ?: it.prootRecoveredOutput,
                                    prootResultUnavailable = output == null && it.prootRecoveredOutput == null,
                                )
                            } else {
                                it
                            }
                        },
                )
            }
            if (output?.acknowledged == true) {
                AutomaticRuntimeCollection.Observation.COMPLETE
            } else {
                AutomaticRuntimeCollection.Observation.RETRY
            }
        }
    }
}

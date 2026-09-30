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
    private val prootStatus: (String, String) -> com.helix.app.proot.ProotRecoveryStatus,
) {
    private val automatic =
        AutomaticRuntimeCollection(scope, onPaused = {
            android.util.Log.w("RuntimeCollection", it)
        })
    private var observedSession: String? = null

    fun collect() {
        val screen = screenState.value
        if (screen.openSessionId != observedSession) {
            automatic.resetPaused()
            observedSession = screen.openSessionId
        }
        collectSubscriptions(screen)
        collectProotResults(screen, automatic, screenState, collectProot, prootStatus)
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
                    if (latest.output?.acknowledged ==
                        true
                    ) {
                        return@request AutomaticRuntimeCollection.Observation.COMPLETE
                    }
                    val status = subscriptionRecovery(row.turnId, row.modelCallId, false)
                    val observation = SubscriptionCollectionPolicy.beforeCollect(status)
                    val output =
                        if (observation == null) {
                            subscriptionResultRecovery(row.turnId, row.modelCallId, false)
                        } else {
                            subscriptionResultRecovery(row.turnId, row.modelCallId, true)
                        }
                    updateSubscriptionRecovery(
                        latest.copy(
                            status = status,
                            output = output ?: latest.output,
                            localResultAvailable = output != null || latest.localResultAvailable,
                            outputUnavailable =
                                output == null && latest.output == null &&
                                    observation != AutomaticRuntimeCollection.Observation.RUNNING,
                        ),
                    )
                    if (output?.acknowledged == true) {
                        AutomaticRuntimeCollection.Observation.COMPLETE
                    } else {
                        observation ?: AutomaticRuntimeCollection.Observation.RETRY
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
    status: (String, String) -> com.helix.app.proot.ProotRecoveryStatus,
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
            val observation = ProotCollectionPolicy.beforeCollect(status(row.turnId, row.callId))
            if (observation != null) return@request observation
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

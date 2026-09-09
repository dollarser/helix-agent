package com.helix.app.chat

import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Serializes explicit recovery actions without restarting a Turn or replaying a job. */
internal class ChatRecoveryActions(
    private val storage: HelixStorage,
    private val workScope: CoroutineScope,
    private val screenState: MutableStateFlow<ChatScreenState>,
    private val subscriptionRecovery: (String, String, Boolean) -> com.helix.app.provider.SubscriptionRecoveryStatus,
    private val subscriptionResultRecovery: (
        String,
        String,
        Boolean,
    ) -> com.helix.app.provider.SubscriptionRecoveredOutput?,
) {
    private val subscriptionRecoveryMutex = kotlinx.coroutines.sync.Mutex()

    fun inspectInterruptedSubscription(
        turnId: String,
        modelCallId: String,
        stop: Boolean,
    ) {
        workScope.launch {
            subscriptionRecoveryMutex.lock()
            try {
                val row =
                    screenState.value.subscriptionRecoveries
                        .singleOrNull { it.turnId == turnId && it.modelCallId == modelCallId } ?: return@launch
                if (row.busy) return@launch
                updateSubscriptionRecovery(row.copy(busy = true))
                val status =
                    try {
                        subscriptionRecovery(turnId, modelCallId, stop)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        updateSubscriptionRecovery(row.copy(busy = false))
                        throw cancelled
                    } catch (_: Exception) {
                        com.helix.app.provider.SubscriptionRecoveryStatus.UNKNOWN
                    }
                updateSubscriptionRecovery(row.copy(status = status))
            } finally {
                subscriptionRecoveryMutex.unlock()
            }
        }
    }

    fun recoverInterruptedSubscriptionResult(
        turnId: String,
        modelCallId: String,
    ) {
        workScope.launch {
            subscriptionRecoveryMutex.lock()
            try {
                val row =
                    screenState.value.subscriptionRecoveries
                        .singleOrNull { it.turnId == turnId && it.modelCallId == modelCallId } ?: return@launch
                if (row.busy) return@launch
                updateSubscriptionRecovery(row.copy(busy = true, outputUnavailable = false))
                val output =
                    try {
                        subscriptionResultRecovery(
                            turnId,
                            modelCallId,
                            row.localResultAvailable &&
                                row.status != com.helix.app.provider.SubscriptionRecoveryStatus.SUCCEEDED_UNVERIFIED,
                        )
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        updateSubscriptionRecovery(row.copy(busy = false))
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                updateSubscriptionRecovery(
                    row.copy(
                        output = output,
                        outputUnavailable = output == null,
                        localResultAvailable =
                            output != null,
                    ),
                )
            } finally {
                subscriptionRecoveryMutex.unlock()
            }
        }
    }

    private fun updateSubscriptionRecovery(row: SubscriptionRecoveryUi) {
        screenState.update { current ->
            current.copy(
                subscriptionRecoveries =
                    current.subscriptionRecoveries.map {
                        if (it.modelCallId == row.modelCallId) row else it
                    },
            )
        }
    }

    fun inspectInterruptedProot(
        turnId: String,
        callId: String,
        stop: Boolean,
    ) {
        workScope.launch {
            if (!com.helix.app.proot.ProotToolModule.AVAILABLE) return@launch
            val row =
                screenState.value.toolTimeline.singleOrNull { it.turnId == turnId && it.callId == callId }
                    ?: return@launch
            if (!row.prootRecoveryAvailable || row.prootRecoveryBusy) return@launch
            updateProotRecovery(callId, true, row.prootRecoveryReport)
            val report =
                try {
                    com.helix.app.proot.ProotToolModule
                        .inspectInterruptedJob(storage, turnId, callId, stop)
                } catch (_: Exception) {
                    com.helix.app.proot.ProotRecoveryReport.Unknown
                }
            updateProotRecovery(callId, false, report)
        }
    }

    fun recoverInterruptedProot(
        turnId: String,
        callId: String,
    ) = recoverProotResult(turnId, callId, false)

    fun retryProotAcknowledgement(
        turnId: String,
        callId: String,
    ) = recoverProotResult(turnId, callId, true)

    private fun recoverProotResult(
        turnId: String,
        callId: String,
        retryAcknowledgement: Boolean,
    ) {
        workScope.launch {
            if (!com.helix.app.proot.ProotToolModule.AVAILABLE) return@launch
            val row =
                screenState.value.toolTimeline.singleOrNull { it.turnId == turnId && it.callId == callId }
                    ?: return@launch
            if (!row.prootRecoveryAvailable || row.prootRecoveryBusy) return@launch
            updateProotRecovery(callId, true, row.prootRecoveryReport)
            val output =
                try {
                    if (retryAcknowledgement) {
                        com.helix.app.proot.ProotToolModule
                            .recoverInterruptedResult(storage, turnId, callId, false)
                    } else {
                        com.helix.app.proot.ProotToolModule
                            .recoverInterruptedResult(storage, turnId, callId, true)
                            ?: com.helix.app.proot.ProotToolModule
                                .recoverInterruptedResult(storage, turnId, callId, false)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    updateProotRecovery(callId, false, row.prootRecoveryReport)
                    throw cancelled
                } catch (_: Exception) {
                    row.prootRecoveredOutput?.copy(acknowledged = false)
                }
            screenState.update { current ->
                current.copy(
                    toolTimeline =
                        current.toolTimeline.map {
                            if (it.callId == callId && it.turnId == turnId) {
                                it.copy(
                                    prootRecoveryBusy = false,
                                    prootRecoveredOutput = output,
                                    prootResultUnavailable = output == null,
                                )
                            } else {
                                it
                            }
                        },
                )
            }
        }
    }

    private fun updateProotRecovery(
        callId: String,
        busy: Boolean,
        report: com.helix.app.proot.ProotRecoveryReport?,
    ) {
        screenState.update { current ->
            current.copy(
                toolTimeline =
                    current.toolTimeline.map {
                        if (it.callId == callId) it.copy(prootRecoveryBusy = busy, prootRecoveryReport = report) else it
                    },
            )
        }
    }
}

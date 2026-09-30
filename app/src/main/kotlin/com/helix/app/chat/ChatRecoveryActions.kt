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
    private val prootCollectionLock = Any()

    private val automaticCollection =
        AutomaticRecoveryCollection(
            workScope,
            screenState,
            subscriptionRecoveryMutex,
            subscriptionRecovery,
            subscriptionResultRecovery,
            ::collectProot,
            ::updateSubscriptionRecovery,
            {
                turn,
                call,
                ->
                com.helix.app.proot.ProotToolModule
                    .inspectInterruptedJob(storage, turn, call, false)
                    .status
            },
        )

    fun collectAutomatically() = automaticCollection.collect()

    /** Original identities only; callable while the conversation is not on screen. */
    @Suppress("TooGenericExceptionCaught") // Executor failure is UNKNOWN, not a success.
    suspend fun inspectOriginal(turnId: String): String {
        val turn = storage.turns.resolve(turnId)
        val observations = RecoveryEvidence { ForbiddenContentGuard.reasonFor(it) != null }
        subscriptionRecoveryMutex.lock()
        try {
            subscriptionRecoveriesFor(storage, turn.sessionId, emptyList()).filter { it.turnId == turnId }.forEach {
                try {
                    val status = subscriptionRecovery(it.turnId, it.modelCallId, false)
                    val output = subscriptionResultRecovery(it.turnId, it.modelCallId, false)
                    observations.add("subscription", it.modelCallId, status.name, output?.pages?.firstOrNull())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    observations.add("subscription", it.modelCallId, "UNKNOWN:${error.javaClass.simpleName}", null)
                }
            }
        } finally {
            subscriptionRecoveryMutex.unlock()
        }
        storage.toolCalls
            .listByTurn(turnId)
            .filter {
                it.name in setOf("bash", "code.linux.run") && it.state in setOf("INTERRUPTED", "NEEDS_REVIEW")
            }.forEach {
                try {
                    val status =
                        com.helix.app.proot.ProotToolModule.inspectInterruptedJob(
                            storage,
                            turnId,
                            it.callId,
                            false,
                        )
                    val output =
                        if (status.status == com.helix.app.proot.ProotRecoveryStatus.SUCCEEDED) {
                            collectProot(turnId, it.callId, false)
                        } else {
                            collectProot(turnId, it.callId, true)
                        }
                    observations.add(
                        "proot.stdout",
                        it.callId,
                        status.status.name,
                        output?.stdout,
                        output?.acknowledged,
                    )
                    if (!output?.stderr.isNullOrBlank()) {
                        observations.add("proot.stderr", it.callId, status.status.name, output.stderr)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    observations.add("proot", it.callId, "UNKNOWN:${error.javaClass.simpleName}", null)
                }
            }
        return observations.render()
    }

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
                        if (it.modelCallId == row.modelCallId && it.turnId == row.turnId) {
                            row.copy(
                                output = row.output ?: it.output,
                                localResultAvailable = row.localResultAvailable || it.localResultAvailable,
                                outputUnavailable = row.outputUnavailable && it.output == null,
                            )
                        } else {
                            it
                        }
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
            updateProotRecovery(screenState, callId, true, row.prootRecoveryReport)
            val report =
                try {
                    com.helix.app.proot.ProotToolModule
                        .inspectInterruptedJob(storage, turnId, callId, stop)
                } catch (_: Exception) {
                    com.helix.app.proot.ProotRecoveryReport.Unknown
                }
            updateProotRecovery(screenState, callId, false, report)
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
            updateProotRecovery(screenState, callId, true, row.prootRecoveryReport)
            val output =
                try {
                    if (retryAcknowledgement) {
                        collectProot(turnId, callId, false)
                    } else {
                        collectProot(turnId, callId, true)
                            ?: collectProot(turnId, callId, false)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    updateProotRecovery(screenState, callId, false, row.prootRecoveryReport)
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

    private fun collectProot(
        turnId: String,
        callId: String,
        localOnly: Boolean,
    ) = synchronized(prootCollectionLock) {
        com.helix.app.proot.ProotToolModule
            .recoverInterruptedResult(storage, turnId, callId, localOnly)
    }
}

private fun updateProotRecovery(
    screenState: MutableStateFlow<ChatScreenState>,
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

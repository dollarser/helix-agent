package com.helix.core.agent.runtime

import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.TerminalCommitCommand
import com.helix.core.agent.requireOutcome
import com.helix.core.agent.turn.CancelTurnCommand
import com.helix.core.agent.turn.ReviewResolutionCommand
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnLifecycleStore
import com.helix.core.agent.turn.TurnRoundCoordinator
import kotlinx.coroutines.CoroutineScope

/** Single lifecycle owner; durable facts belong to the store, live handles are projections. */
@Suppress("TooManyFunctions")
class TurnEngine(
    val store: TurnLifecycleStore,
    private val diagnostics: TurnDiagnostics,
) {
    val executionGate = Any()
    private val observations = TurnObservationHub()
    val runtimeView: TurnRuntimeView =
        object : TurnRuntimeView, TurnStateReader by store {
            override fun observe(turnId: String) = observations.observe(turnId)
        }
    val liveExecution = TurnLiveRegistry()
    val liveHandles = liveExecution.handles
    private val systemStops = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val executionDriver =
        TurnExecutionDriver(
            store,
            liveExecution,
            observations,
            ::parkForReview,
            ::settleTerminal,
            systemStops::remove,
            diagnostics,
        )

    fun launchExecution(
        scope: CoroutineScope,
        loop: TurnLoopRunner,
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
    ): String = executionDriver.launch(scope, loop, request, hooks)

    fun publishObservation(observation: TurnObservation) = observations.emit(observation)

    fun requestSystemStop(
        turnId: String,
        reason: String,
    ) {
        require(turnId.isNotBlank() && reason.isNotBlank())
        systemStops[turnId] = reason
    }

    fun clearSystemStop(turnId: String) {
        systemStops.remove(turnId)
    }

    fun submissionReceipt(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ) = store.submissionReceipt(clientRequestId, sessionId, inputFingerprint)

    fun recoveryPredecessor(
        sessionId: String,
        clientRequestId: String,
    ) = store.recoveryPredecessor(sessionId, clientRequestId)

    fun admit(command: TurnAdmissionCommand) = store.admit(command)

    suspend fun resolveReview(command: ReviewResolutionCommand) = store.resolveReview(command)

    fun sessionBlocker(sessionId: String) = store.sessionBlocker(sessionId)

    fun recoverOnStartup() = store.recoverOnStartup()

    fun finishAutomaticRecovery(
        parentId: String,
        reason: String,
        notice: String? = null,
    ) = store.finishAutomaticRecovery(parentId, reason, notice)

    fun requestCancel(
        turnId: String,
        sessionId: String,
        hasLiveDriver: Boolean,
        parkTerminalDelivery: Boolean,
        reason: String,
    ) = store.cancel(CancelTurnCommand(turnId, sessionId, hasLiveDriver, parkTerminalDelivery, reason))

    fun parkForReview(
        coordinator: TurnRoundCoordinator,
        sessionId: String,
        reviewCallIds: List<String>,
    ) {
        require(coordinator.terminalCheckpoint().sessionId == sessionId)
        store.parkForReview(coordinator.reviewCheckpoint(reviewCallIds))
        coordinator.markRuntimeParked(reviewCallIds)
    }

    suspend fun settleTerminal(
        coordinator: TurnRoundCoordinator,
        outcome: ModelStreamTerminal,
    ): ModelStreamTerminal {
        val settled =
            store
                .commitTerminal(
                    TerminalCommitCommand(coordinator.terminalCheckpoint(), outcome),
                ).requireOutcome()
        coordinator.markTerminalCommitted(settled.state)
        return settled
    }
}

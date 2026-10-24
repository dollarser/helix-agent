package com.helix.core.agent.runtime

import com.helix.core.agent.AgentLoop
import com.helix.core.agent.ContextCapacityException
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnLoopResult
import com.helix.core.agent.turn.TurnLifecycleStore
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.agent.turn.TurnTerminalReleaseReceipt
import com.helix.core.model.ApprovalWaitCancelledException
import com.helix.core.model.ErrorCode
import com.helix.core.model.TurnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

data class TurnExecutionRequest(
    val sessionId: String,
    val coordinator: TurnRoundCoordinator,
    val providerId: String,
    val retryTurnId: String?,
    val control: RunControlConfig,
    val entry: AgentLoop.Entry = AgentLoop.Entry.INITIAL,
)

data class TurnTerminalProjection(
    val continueDelivery: Boolean,
    val errorLabel: String?,
)

/**
 * Application seams around the durable execution boundary.
 *
 * before-* hooks run after the durable settlement and before live ownership is released, so
 * process-local queue/Goal handoff state cannot race a successor admission. after-* hooks are
 * projection/notification only and are isolated from lifecycle truth.
 */
interface TurnExecutionHooks {
    fun beforeExecution(turnId: String)

    fun beforeReviewRelease(turnId: String): String?

    fun afterReviewRelease(turnId: String)

    fun beforeTerminalRelease(
        turnId: String,
        outcome: ModelStreamTerminal,
        release: TurnTerminalReleaseReceipt,
    ): TurnTerminalProjection

    fun afterTerminalRelease(
        turnId: String,
        outcome: ModelStreamTerminal,
        continueDelivery: Boolean,
    )

    fun beforeUnknownRelease(turnId: String)

    fun afterUnknownRelease(turnId: String)
}

/** Engine-owned live driver: coroutine, AgentLoop invocation, settlement and owner release. */
internal class TurnExecutionDriver(
    private val store: TurnLifecycleStore,
    private val liveExecution: TurnLiveRegistry,
    private val observations: TurnObservationHub,
    private val parkForReview: (TurnRoundCoordinator, String, List<String>) -> Unit,
    private val settleTerminal: suspend (TurnRoundCoordinator, ModelStreamTerminal) -> ModelStreamTerminal,
    private val consumeSystemStop: (String) -> String?,
    private val diagnostics: TurnDiagnostics,
) {
    fun launch(
        scope: CoroutineScope,
        loop: TurnLoopRunner,
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
    ): String {
        val turnId = request.coordinator.id
        val startGate = TurnLaunchGate()
        val job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                run(loop, request, hooks, startGate)
            }
        startGate.prepare {
            liveExecution.claim(request.sessionId, turnId, job, request.control)
            observations.open(turnId)
            hooks.beforeExecution(turnId)
        }
        return turnId
    }

    // Classify once after shared uncertainty checks; settlement failures have a separate boundary.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private suspend fun run(
        loop: TurnLoopRunner,
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        startGate: TurnLaunchGate,
    ) {
        val turnId = request.coordinator.id
        // Catch model/host execution failures separately from durable settlement failures.
        // A settlement exception must never be interpreted as a new model failure to settle again.
        val result =
            try {
                startGate.await()
                loop.run(request)
            } catch (failure: Exception) {
                if (failure is GoalTimeLimitException) liveExecution.byTurn(turnId)?.signalCancel()
                if (failure is com.helix.core.agent.TurnCommitRejectedException) {
                    failClosedUnknown(request, hooks, failure)
                } else if (!preserveKnownReviewUncertainty(request, hooks, failure)) {
                    val outcome =
                        when (failure) {
                            is ContextCapacityException -> {
                                ModelStreamTerminal(TurnState.FAILED, failure.code)
                            }

                            is GoalTimeLimitException -> {
                                ModelStreamTerminal(TurnState.FAILED, failure.code)
                            }

                            is CancellationException, is ApprovalWaitCancelledException -> {
                                ModelStreamTerminal(TurnState.CANCELLED, null)
                            }

                            else -> {
                                report("turn $turnId failed at the model boundary", failure)
                                ModelStreamTerminal(TurnState.FAILED, ErrorCode.INTERNAL.name)
                            }
                        }
                    terminalizeSafely(request, hooks, outcome)
                }
                if (failure is CancellationException) throw failure
                return
            }
        when (result) {
            is TurnLoopResult.Terminal -> {
                terminalizeSafely(request, hooks, result.outcome)
            }

            is TurnLoopResult.ParkedForReview -> {
                runCatching { parkReview(request, hooks, result.callIds) }
                    .onFailure { failClosedUnknown(request, hooks, it) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Persistence failure remains uncertain, never a retry.
    private suspend fun terminalizeSafely(
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        outcome: ModelStreamTerminal,
    ) {
        try {
            terminalize(request, hooks, outcome)
        } catch (failure: CancellationException) {
            failClosedUnknown(request, hooks, failure)
            throw failure
        } catch (failure: Exception) {
            failClosedUnknown(request, hooks, failure)
        }
    }

    private fun preserveKnownReviewUncertainty(
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        failure: Throwable,
    ): Boolean {
        val coordinator = request.coordinator
        val reviewCallIds = coordinator.settledReviewCallIds()
        return when {
            reviewCallIds != null -> {
                runCatching { parkReview(request, hooks, reviewCallIds) }
                    .onFailure { failClosedUnknown(request, hooks, it) }
                true
            }

            coordinator.hasUnknownBatch() -> {
                failClosedUnknown(request, hooks, failure)
                true
            }

            else -> {
                false
            }
        }
    }

    private fun parkReview(
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        callIds: List<String>,
    ) {
        val turnId = request.coordinator.id
        parkForReview(request.coordinator, request.sessionId, callIds)
        val label =
            runCatching { hooks.beforeReviewRelease(turnId) }
                .onFailure { report("pre-release review cleanup error for turn $turnId", it) }
                .getOrNull()
        liveExecution.release(request.sessionId, turnId)
        observations.emit(TurnObservation(turnId, TurnState.NEEDS_REVIEW, errorLabel = label))
        observations.close(turnId)
        runCatching { hooks.afterReviewRelease(turnId) }
            .onFailure { report("post-review notification error for turn $turnId", it) }
    }

    private suspend fun terminalize(
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        outcome: ModelStreamTerminal,
    ) {
        val turnId = request.coordinator.id
        val effective = consumeSystemStop(turnId)?.let { outcome.copy(errorCode = it) } ?: outcome
        val settled = settleTerminal(request.coordinator, effective)
        // Settlement has committed. Preparation failure cannot re-enter terminal settlement.
        val prepared =
            runCatching { store.prepareTerminalRelease(turnId, request.sessionId, settled) }
                .onFailure { report("post-terminal delivery preparation failed for $turnId", it) }
                .getOrNull()
        if (prepared == null) {
            runCatching { store.parkFailedTerminalDelivery(turnId, request.sessionId) }
                .onFailure { report("terminal delivery parking failed for $turnId", it) }
            runCatching { hooks.beforeUnknownRelease(turnId) }
                .onFailure { report("terminal delivery failure cleanup failed for $turnId", it) }
        }
        val projection =
            runCatching {
                if (prepared == null) {
                    TurnTerminalProjection(false, null)
                } else {
                    hooks.beforeTerminalRelease(turnId, settled, prepared)
                }
            }.onFailure { report("pre-release terminal cleanup error for turn $turnId", it) }
                .getOrDefault(TurnTerminalProjection(continueDelivery = false, errorLabel = null))
        liveExecution.release(request.sessionId, turnId)
        observations.emit(
            TurnObservation(
                turnId = turnId,
                state = settled.state,
                errorLabel = projection.errorLabel,
                retryable = settled.state == TurnState.FAILED,
            ),
        )
        runCatching {
            hooks.afterTerminalRelease(turnId, settled, prepared != null && projection.continueDelivery)
        }.onFailure {
            report("post-terminal notification error for turn $turnId", it)
        }
    }

    private fun failClosedUnknown(
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        failure: Throwable,
    ) {
        val turnId = request.coordinator.id
        report("turn $turnId could not persist review park; keeping uncertainty fail-closed", failure)
        runCatching {
            store.parkUnknownDelivery(turnId, request.sessionId)
        }.onFailure {
            report("could not park queued input after review-park failure", it)
        }
        runCatching { hooks.beforeUnknownRelease(turnId) }
            .onFailure { report("pre-release unknown cleanup error for turn $turnId", it) }
        liveExecution.release(request.sessionId, turnId)
        observations.close(turnId)
        runCatching { hooks.afterUnknownRelease(turnId) }
            .onFailure { report("could not refresh fail-closed review state for turn $turnId", it) }
    }

    private fun report(
        message: String,
        failure: Throwable?,
    ) {
        // Diagnostic sinks are observers, never a second lifecycle failure/retry path.
        runCatching { diagnostics.report(message, failure) }
    }
}

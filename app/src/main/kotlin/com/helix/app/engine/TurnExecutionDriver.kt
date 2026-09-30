package com.helix.app.engine

import android.util.Log
import com.helix.app.agent.AgentLoop
import com.helix.app.agent.AutomaticGoalContinuation
import com.helix.app.agent.ContextCapacityException
import com.helix.app.agent.GoalTimeLimitException
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnLoopResult
import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.model.Clock
import com.helix.core.model.ErrorCode
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal data class TurnExecutionRequest(
    val sessionId: String,
    val coordinator: TurnCoordinator,
    val providerId: String,
    val retryTurnId: String?,
    val control: RunControlConfig,
    val entry: AgentLoop.Entry = AgentLoop.Entry.INITIAL,
)

internal data class TurnTerminalProjection(
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
internal interface TurnExecutionHooks {
    fun beforeExecution(turnId: String)

    fun beforeReviewRelease(turnId: String): String?

    fun afterReviewRelease(turnId: String)

    fun beforeTerminalRelease(
        turnId: String,
        outcome: ModelStreamTerminal,
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
    private val storage: HelixStorage,
    private val clock: Clock,
    private val liveExecution: TurnLiveRegistry,
    private val observations: TurnObservationHub,
    private val parkForReview: (TurnCoordinator, String, List<String>) -> Unit,
    private val settleTerminal: (TurnCoordinator, ModelStreamTerminal) -> ModelStreamTerminal,
    private val consumeSystemStop: (String) -> String?,
) {
    fun launch(
        scope: CoroutineScope,
        loop: AgentLoop,
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

    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(
        loop: AgentLoop,
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        startGate: TurnLaunchGate,
    ) {
        val coordinator = request.coordinator
        val turnId = coordinator.id
        try {
            startGate.await()
            when (
                val result =
                    loop.runWithGoalTime(turnId) {
                        loop.runToolLoop(
                            request.sessionId,
                            coordinator,
                            request.providerId,
                            request.retryTurnId,
                            request.control,
                            request.entry,
                        )
                    }
            ) {
                is TurnLoopResult.Terminal -> terminalize(request, hooks, result.outcome)
                is TurnLoopResult.ParkedForReview -> parkReview(request, hooks, result.callIds)
            }
        } catch (e: ContextCapacityException) {
            terminalize(request, hooks, ModelStreamTerminal(TurnState.FAILED, e.code))
        } catch (e: GoalTimeLimitException) {
            liveExecution.byTurn(turnId)?.signalCancel()
            if (!preserveKnownReviewUncertainty(request, hooks, e)) {
                terminalize(request, hooks, ModelStreamTerminal(TurnState.FAILED, e.code))
            }
        } catch (e: CancellationException) {
            if (!preserveKnownReviewUncertainty(request, hooks, e)) {
                terminalize(request, hooks, ModelStreamTerminal(TurnState.CANCELLED, null))
            }
            throw e
        } catch (e: ApprovalCancelledException) {
            Log.i(TAG, "turn $turnId stopped while awaiting approval: ${e.message}")
            if (!preserveKnownReviewUncertainty(request, hooks, e)) {
                terminalize(request, hooks, ModelStreamTerminal(TurnState.CANCELLED, null))
            }
        } catch (e: Exception) {
            if (!preserveKnownReviewUncertainty(request, hooks, e)) {
                Log.e(TAG, "turn $turnId failed at the model boundary", e)
                terminalize(
                    request,
                    hooks,
                    ModelStreamTerminal(TurnState.FAILED, ErrorCode.INTERNAL.name),
                )
            }
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
                .onFailure { Log.e(TAG, "pre-release review cleanup error for turn $turnId", it) }
                .getOrNull()
        liveExecution.release(request.sessionId, turnId)
        observations.emit(TurnObservation(turnId, TurnState.NEEDS_REVIEW, errorLabel = label))
        observations.close(turnId)
        runCatching { hooks.afterReviewRelease(turnId) }
            .onFailure { Log.e(TAG, "post-review notification error for turn $turnId", it) }
    }

    private fun terminalize(
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        outcome: ModelStreamTerminal,
    ) {
        val turnId = request.coordinator.id
        val effective = consumeSystemStop(turnId)?.let { outcome.copy(errorCode = it) } ?: outcome
        val settled = settleTerminal(request.coordinator, effective)
        val turn = storage.turns.resolve(turnId)
        val goalLocalLimit =
            storage.goalTurnBindings.byTurn(turnId) != null &&
                AutomaticGoalContinuation.accepts(settled.state.name, settled.errorCode, turn.pauseRequestedAt)
        if (settled.state != TurnState.COMPLETED && !goalLocalLimit) {
            storage.sessionInputs.parkSessionInputs(
                request.sessionId,
                "TURN_NOT_COMPLETED",
                clock.now().toEpochMilli(),
            )
        }
        val projection =
            runCatching { hooks.beforeTerminalRelease(turnId, settled) }
                .onFailure { Log.e(TAG, "pre-release terminal cleanup error for turn $turnId", it) }
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
            hooks.afterTerminalRelease(turnId, settled, projection.continueDelivery)
        }.onFailure {
            Log.e(TAG, "post-terminal notification error for turn $turnId", it)
        }
    }

    private fun failClosedUnknown(
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
        failure: Throwable,
    ) {
        val turnId = request.coordinator.id
        Log.e(TAG, "turn $turnId could not persist review park; keeping uncertainty fail-closed", failure)
        runCatching {
            storage.sessionInputs.parkSessionInputs(
                request.sessionId,
                "TURN_NEEDS_REVIEW",
                clock.now().toEpochMilli(),
            )
        }.onFailure {
            Log.e(TAG, "could not park queued input after review-park failure", it)
        }
        runCatching { hooks.beforeUnknownRelease(turnId) }
            .onFailure { Log.e(TAG, "pre-release unknown cleanup error for turn $turnId", it) }
        liveExecution.release(request.sessionId, turnId)
        observations.close(turnId)
        runCatching { hooks.afterUnknownRelease(turnId) }
            .onFailure { Log.e(TAG, "could not refresh fail-closed review state for turn $turnId", it) }
    }

    private companion object {
        const val TAG = "TurnExecutionDriver"
    }
}

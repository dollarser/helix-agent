package com.helix.app.engine

import com.helix.app.agent.AgentLoop
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.review.TurnReviewResolutionResult
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.CoroutineScope

/**
 * Durable + live Turn lifecycle owner.
 *
 * Admission, execution ownership, AgentLoop driving, settlement, cancellation and observation all
 * converge here. Room remains durable truth; process-local handles/observations are projections.
 */
@Suppress("TooManyFunctions") // Lifecycle facade delegates to focused Engine collaborators.
class TurnEngine internal constructor(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    private val admission = TurnAdmission(storage, clock, idGenerator)
    private val reviewResolution = TurnReviewResolution(storage, clock, idGenerator)
    private val reviewParking = TurnReviewParking(storage, clock, idGenerator)
    private val settlement = TurnSettlement(storage, clock, idGenerator)
    private val observations = TurnObservationHub()
    internal val runtimeView: TurnRuntimeView = StorageTurnRuntimeView(storage, observations)
    private val systemStops = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Process-local execution handles only; Room remains the durable source of truth. */
    internal val liveExecution = TurnLiveRegistry()

    internal val liveHandles = liveExecution.handles

    private val executionDriver by lazy {
        TurnExecutionDriver(
            storage = storage,
            clock = clock,
            liveExecution = liveExecution,
            observations = observations,
            parkForReview = ::parkForReview,
            settleTerminal = ::settleTerminal,
            consumeSystemStop = systemStops::remove,
        )
    }

    internal fun launchExecution(
        scope: CoroutineScope,
        loop: AgentLoop,
        request: TurnExecutionRequest,
        hooks: TurnExecutionHooks,
    ): String = executionDriver.launch(scope, loop, request, hooks)

    internal fun publishObservation(observation: TurnObservation) {
        observations.emit(observation)
    }

    internal fun requestSystemStop(
        turnId: String,
        reason: String,
    ) {
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        systemStops[turnId] = reason
    }

    internal fun clearSystemStop(turnId: String) {
        systemStops.remove(turnId)
    }

    internal fun submissionReceipt(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ): SubmitReceiptDecision = admission.receipt(clientRequestId, sessionId, inputFingerprint)

    internal fun recoveryPredecessor(
        sessionId: String,
        clientRequestId: String,
    ): String? = admission.recoveryPredecessor(sessionId, clientRequestId)

    internal fun admit(
        spec: TurnStartSpec,
        control: RunControlConfig,
        wakeReason: GoalWakeReason,
        providerId: String,
        modelId: String,
        freshGuard: () -> Boolean = { true },
        resolveGoal: () -> String? = { null },
    ): TurnAdmissionResult =
        admission.start(
            spec = spec,
            control = control,
            wakeReason = wakeReason,
            providerId = providerId,
            modelId = modelId,
            freshGuard = freshGuard,
            resolveGoal = resolveGoal,
        )

    internal suspend fun resolveReview(command: ReviewResolutionCommand): TurnReviewResolutionResult =
        reviewResolution.resolve(command)

    internal fun sessionBlocker(sessionId: String): SessionTurnBlocker? = DurableSessionGate(storage).blocker(sessionId)

    internal fun recoverOnStartup(): TurnRecovery.Report = TurnRecovery(storage, clock).recover()

    /** Atomically parks the Turn, bound Goal and pending session delivery before dropping the live driver. */
    internal fun parkForReview(
        coordinator: TurnCoordinator,
        sessionId: String,
        reviewCallIds: List<String>,
    ) {
        val checkpoint = coordinator.reviewCheckpoint(reviewCallIds)
        storage.withTransaction {
            val turn = storage.turns.resolve(coordinator.id)
            require(turn.sessionId == sessionId) { "turn/session mismatch" }
            reviewParking.persist(checkpoint)
            storage.sessionInputs.parkSessionInputs(
                sessionId,
                "TURN_NEEDS_REVIEW",
                clock.now().toEpochMilli(),
            )
        }
        coordinator.markRuntimeParked(reviewCallIds)
    }

    /** The only production entry that commits a live Turn terminal. */
    internal fun settleTerminal(
        coordinator: TurnCoordinator,
        outcome: ModelStreamTerminal,
    ): ModelStreamTerminal {
        val settled = settlement.settle(coordinator.terminalCheckpoint(), outcome)
        coordinator.markTerminalCommitted(settled.state)
        return settled
    }

    /** Transaction-owned terminal write seam used by the Steer-vs-final-answer linearization. */
    internal fun persistTerminalInTransaction(
        checkpoint: com.helix.app.agent.TurnTerminalCheckpoint,
        outcome: ModelStreamTerminal,
    ): ModelStreamTerminal = settlement.settleInTransaction(checkpoint, outcome)

    /**
     * Durable cancellation admission. The caller supplies only whether this process still owns the
     * live execution handle; all state/queue decisions are re-read and committed from Room.
     */
    internal fun requestCancel(
        turnId: String,
        sessionId: String,
        hasLiveDriver: Boolean,
        parkTerminalDelivery: Boolean,
        reason: String,
    ): EngineCancelDecision {
        lateinit var decision: EngineCancelDecision
        storage.withTransaction {
            val turn = storage.turns.resolve(turnId)
            require(turn.sessionId == sessionId) { "turn/session mismatch" }
            val phase = TurnState.valueOf(turn.state)
            decision =
                when {
                    phase.isTerminal -> {
                        if (parkTerminalDelivery) {
                            storage.sessionInputs.parkSessionInputs(sessionId, reason, clock.now().toEpochMilli())
                        }
                        EngineCancelDecision.AlreadyTerminal(phase)
                    }

                    phase == TurnState.NEEDS_REVIEW -> {
                        EngineCancelDecision.ReviewRequired
                    }

                    hasLiveDriver -> {
                        storage.sessionInputs.parkSessionInputs(sessionId, reason, clock.now().toEpochMilli())
                        if (phase != TurnState.CANCELLING) {
                            storage.turns.updateState(
                                turn,
                                TurnState.CANCELLING,
                                turn.stepCount,
                                null,
                                null,
                            )
                        }
                        EngineCancelDecision.SignalLive
                    }

                    else -> {
                        error("non-terminal Turn has no live driver and is not parked: $phase")
                    }
                }
        }
        return decision
    }
}

internal sealed interface EngineCancelDecision {
    data class AlreadyTerminal(
        val phase: TurnState,
    ) : EngineCancelDecision

    data object SignalLive : EngineCancelDecision

    data object ReviewRequired : EngineCancelDecision
}

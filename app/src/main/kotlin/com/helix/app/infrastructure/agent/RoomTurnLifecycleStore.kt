package com.helix.app.infrastructure.agent

import com.helix.app.agent.AutomaticGoalContinuation
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.TerminalCommitCommand
import com.helix.core.agent.TurnReviewCheckpoint
import com.helix.core.agent.turn.CancelTurnCommand
import com.helix.core.agent.turn.ReviewResolutionCommand
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnCancellationReceipt
import com.helix.core.agent.turn.TurnLifecycleSnapshot
import com.helix.core.agent.turn.TurnLifecycleStore
import com.helix.core.agent.turn.TurnTerminalReleaseReceipt
import com.helix.core.model.Clock
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** The single Room lifecycle adapter. Durable facts commit before Core signals or releases owners. */
@Suppress("TooManyFunctions") // Implements one closed durable lifecycle port.
internal class RoomTurnLifecycleStore(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) : TurnLifecycleStore {
    private val admission = TurnAdmission(storage, clock, idGenerator)
    private val settlement = TurnSettlement(storage, clock, idGenerator)
    private val reviewParking = TurnReviewParking(storage, clock, idGenerator)
    private val reviewResolution = TurnReviewResolution(storage, clock, idGenerator)

    override fun admit(command: TurnAdmissionCommand) = admission.start(command)

    override fun submissionReceipt(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ) = admission.receipt(clientRequestId, sessionId, inputFingerprint)

    override fun recoveryPredecessor(
        sessionId: String,
        clientRequestId: String,
    ) = admission.recoveryPredecessor(sessionId, clientRequestId)

    override fun sessionBlocker(sessionId: String) = DurableSessionGate(storage).blocker(sessionId)

    override suspend fun commitTerminal(command: TerminalCommitCommand) = settlement.commitTerminal(command)

    override suspend fun resolveReview(command: ReviewResolutionCommand) = reviewResolution.resolve(command)

    override fun recoverOnStartup() = TurnRecovery(storage, clock).recover()

    override fun finishAutomaticRecovery(
        parentId: String,
        reason: String,
        inspectionFailed: Boolean,
    ) = AutomaticRecoverySettlement(storage, clock).finish(parentId, reason, inspectionFailed)

    override fun findTurn(turnId: String): TurnLifecycleSnapshot? =
        storage.turns.find(turnId)?.let {
            TurnLifecycleSnapshot(it.id, it.sessionId, TurnState.valueOf(it.state))
        }

    override fun persistedPhase(turnId: String): TurnState? = findTurn(turnId)?.phase

    override fun persistedAssistantText(turnId: String): String? {
        val turn = storage.turns.find(turnId) ?: return null
        return storage.messages
            .listBySession(turn.sessionId)
            .lastOrNull { it.turnId == turnId && it.role == ModelRole.ASSISTANT.name }
            ?.let(storage.messages::readContent)
            ?.takeIf(String::isNotBlank)
    }

    override fun requestGoalPause(turnId: String): Boolean =
        storage.goalTurnBindings.byTurn(turnId) != null &&
            storage.turns.requestPause(turnId, clock.now().toEpochMilli())

    override fun parkForReview(checkpoint: TurnReviewCheckpoint) {
        storage.withTransaction {
            reviewParking.persist(checkpoint)
            storage.sessionInputs.parkSessionInputs(
                checkpoint.sessionId,
                "TURN_NEEDS_REVIEW",
                clock.now().toEpochMilli(),
            )
        }
    }

    override fun prepareTerminalRelease(
        turnId: String,
        sessionId: String,
        outcome: ModelStreamTerminal,
    ): TurnTerminalReleaseReceipt {
        var receipt: TurnTerminalReleaseReceipt? = null
        storage.withTransaction {
            val turn = storage.turns.resolve(turnId)
            require(turn.sessionId == sessionId) { "turn/session mismatch" }
            val goalLocalLimit =
                storage.goalTurnBindings.byTurn(turnId) != null &&
                    AutomaticGoalContinuation.accepts(outcome.state.name, outcome.errorCode, turn.pauseRequestedAt)
            if (outcome.state != TurnState.COMPLETED && !goalLocalLimit) {
                storage.sessionInputs.parkSessionInputs(sessionId, "TURN_NOT_COMPLETED", clock.now().toEpochMilli())
            }
            val goalsToReconcile =
                buildSet {
                    storage.goalTurnBindings.byTurn(turnId)?.let { add(storage.goalRuns.resolve(it.runId).goalId) }
                    storage.goalControls.pendingForTurn(turnId).forEach { add(it.goalId) }
                }
            val discarded = RoomGoalEditSettlement(storage, clock, idGenerator).settle(turnId)
            goalsToReconcile.forEach {
                com.helix.app.application.agent
                    .GoalRoundAllowance(
                        storage,
                    ).blockIfExhausted(it)
            }
            storage.sessionInputs
                .listPending(sessionId)
                .filter {
                    it.delivery == com.helix.core.storage.repository.SessionInputDelivery.STEER &&
                        it.expectedTurnId == turnId &&
                        it.state == com.helix.core.storage.repository.SessionInputState.PENDING
                }.forEach {
                    storage.sessionInputs.markNeedsAttention(
                        it.inputId,
                        it.revision,
                        "STEER_TARGET_FINISHED",
                        clock.now().toEpochMilli(),
                    )
                }
            val parent = turn.recoveryFromTurnId.takeIf { AutomaticRecoveryPolicy.isInspection(turn) }
            if (parent !=
                null
            ) {
                AutomaticRecoverySettlement(storage, clock).finish(
                    parent,
                    "RECOVERY_INSPECTION_FINISHED",
                    outcome.state != TurnState.COMPLETED,
                )
            }
            receipt = TurnTerminalReleaseReceipt(discarded, parent)
        }
        return requireNotNull(receipt)
    }

    override fun parkFailedTerminalDelivery(
        turnId: String,
        sessionId: String,
    ) {
        storage.withTransaction {
            val turn = storage.turns.resolve(turnId)
            require(turn.sessionId == sessionId && TurnState.valueOf(turn.state).isTerminal)
            storage.sessionInputs.parkSessionInputs(sessionId, "TURN_RELEASE_FAILED", clock.now().toEpochMilli())
        }
    }

    override fun parkUnknownDelivery(
        turnId: String,
        sessionId: String,
    ) {
        storage.withTransaction {
            require(storage.turns.resolve(turnId).sessionId == sessionId) { "turn/session mismatch" }
            storage.sessionInputs.parkSessionInputs(sessionId, "TURN_NEEDS_REVIEW", clock.now().toEpochMilli())
        }
    }

    override fun cancel(command: CancelTurnCommand): TurnCancellationReceipt {
        var decision: TurnCancellationReceipt = TurnCancellationReceipt.NotFound
        storage.withTransaction {
            val turn = storage.turns.find(command.turnId) ?: return@withTransaction
            require(turn.sessionId == command.sessionId) { "turn/session mismatch" }
            val phase = TurnState.valueOf(turn.state)
            recordUserStop(turn, phase, command.reason, command.parkTerminalDelivery)
            decision =
                when {
                    phase.isTerminal -> {
                        if (command.parkTerminalDelivery) {
                            storage.sessionInputs.parkSessionInputs(
                                command.sessionId,
                                command.reason,
                                clock.now().toEpochMilli(),
                            )
                        }
                        TurnCancellationReceipt.AlreadyTerminal(phase)
                    }

                    phase == TurnState.NEEDS_REVIEW -> {
                        TurnCancellationReceipt.ReviewRequired
                    }

                    command.hasLiveDriver -> {
                        storage.sessionInputs.parkSessionInputs(
                            command.sessionId,
                            command.reason,
                            clock.now().toEpochMilli(),
                        )
                        if (phase !=
                            TurnState.CANCELLING
                        ) {
                            storage.turns.updateState(turn, TurnState.CANCELLING, turn.stepCount, null, null)
                        }
                        TurnCancellationReceipt.SignalLive
                    }

                    else -> {
                        error("non-terminal Turn has no live driver and is not parked: $phase")
                    }
                }
        }
        return decision
    }

    private fun recordUserStop(
        turn: com.helix.core.storage.entity.TurnEntity,
        phase: TurnState,
        reason: String,
        ownsDelivery: Boolean,
    ) {
        if (reason != "USER_STOP") return
        if (!phase.isTerminal || ownsDelivery) storage.sessionInputs.revokeSkillActivations(turn.id)
        if (phase == TurnState.NEEDS_REVIEW || phase == TurnState.INTERRUPTED) {
            storage.turns.updateState(turn, phase, turn.stepCount, turn.endedAt, reason)
            storage.sessionInputs.parkSessionInputs(turn.sessionId, reason, clock.now().toEpochMilli())
        } else if (!phase.isTerminal) {
            storage.turns.requestPause(turn.id, clock.now().toEpochMilli())
        }
    }
}

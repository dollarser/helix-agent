package com.helix.app.infrastructure.agent

import com.helix.app.agent.TurnStartSpec
import com.helix.app.application.agent.GoalRunCoordinator
import com.helix.app.application.agent.GoalTurnStart
import com.helix.app.infrastructure.agent.RoomTurnRoundStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.turn.AdmittedTurn
import com.helix.core.agent.turn.SubmitReceiptDecision
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnAdmissionResult
import com.helix.core.agent.turn.TurnGoalIntent
import com.helix.core.agent.turn.TurnHistoryMutation
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.storage.repository.SessionInputState

/** Durable Turn-start boundary: receipt/session/history/Goal/Turn/runtime facts commit together. */
@Suppress("TooManyFunctions") // Focused helpers share one atomic admission; none is an alternate writer.
internal class TurnAdmission(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    fun recoveryPredecessor(
        sessionId: String,
        clientRequestId: String,
    ): String? {
        val existing = storage.turns.resolveByClientRequestId(clientRequestId)
        if (existing != null && existing.sessionId == sessionId) return existing.recoveryFromTurnId
        val latest = storage.turns.listBySession(sessionId).lastOrNull()
        return latest?.id?.takeIf {
            TurnState.valueOf(latest.state) in setOf(TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW)
        }
    }

    fun receipt(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ): SubmitReceiptDecision =
        submitReceiptDecision(
            storage.turns.resolveByClientRequestId(clientRequestId),
            sessionId,
            inputFingerprint,
        )

    fun start(command: TurnAdmissionCommand): TurnAdmissionResult {
        val identity = command.identity
        val spec = command.toStartSpec()
        var result: TurnAdmissionResult? = null
        storage.withTransaction {
            when (val receipt = receipt(spec)) {
                is SubmitReceiptDecision.Deduplicated -> {
                    result = TurnAdmissionResult.Deduplicated(receipt.turnId)
                    return@withTransaction
                }

                SubmitReceiptDecision.Conflict -> {
                    result = TurnAdmissionResult.Conflict
                    return@withTransaction
                }

                SubmitReceiptDecision.Fresh -> {
                    Unit
                }
            }
            DurableSessionGate(storage).blocker(identity.sessionId)?.let {
                result = TurnAdmissionResult.Blocked(it)
                return@withTransaction
            }
            if (!expectationsMatch(command)) {
                result = TurnAdmissionResult.FreshRejected
                return@withTransaction
            }
            result = startFresh(command, spec)
        }
        return requireNotNull(result) { "Turn admission produced no decision" }
    }

    /** Caller owns the admission transaction and has checked receipt, session and expectations. */
    private fun startFresh(
        command: TurnAdmissionCommand,
        spec: TurnStartSpec,
    ): TurnAdmissionResult {
        validateRecoveryPredecessor(spec)
        val goalId = resolveGoal(command)
        val inspection = command.recovery?.inspection == true
        if (inspection) validateInspection(spec, command.control, goalId)
        applyHistoryMutation(spec, goalId)
        val goalStart =
            goalId?.let {
                GoalRunCoordinator(storage, clock, idGenerator).start(
                    GoalTurnStart(it, command.wakeReason, spec, command.control.budgets, inspection),
                )
            }
        if (goalId != null && goalStart == null) {
            return TurnAdmissionResult.GoalRefused
        }
        val coordinator =
            goalStart?.coordinator ?: RoomTurnRoundStore.start(
                storage,
                clock,
                idGenerator,
                spec,
            )
        val effectiveControl =
            goalStart?.let {
                command.control.copy(
                    mode = if (inspection) AgentMode.PLAN else AgentMode.GOAL,
                    budgets = it.budgets,
                )
            } ?: command.control
        persistRuntime(command, effectiveControl)
        return TurnAdmissionResult.Started(AdmittedTurn(coordinator, effectiveControl, goalId))
    }

    private fun persistRuntime(
        command: TurnAdmissionCommand,
        control: com.helix.core.agent.RunControlConfig,
    ) {
        val runtimeRecord =
            TurnRuntimeRecordCodec.startRecord(
                turnId = command.identity.turnId,
                providerId = command.provider.providerId,
                modelId = command.provider.modelId,
                providerSnapshot = command.provider.snapshot,
                control = control,
                expert = storage.sessionExperts.forSession(command.identity.sessionId),
            )
        storage.turnRuntimeRecords.create(runtimeRecord)
    }

    private fun expectationsMatch(command: TurnAdmissionCommand): Boolean {
        val session = storage.sessions.find(command.identity.sessionId) ?: return false
        val sessionMatches =
            session.archivedAt == null &&
                session.providerId == command.provider.providerId && session.modelId == command.expectedSessionModelId
        val recoveryMatches =
            command.recovery?.takeIf { it.inspection }?.let {
                storage.turns.find(it.parentTurnId)?.let(AutomaticRecoveryPolicy::eligible) == true
            } ?: true
        return sessionMatches && queueMatches(command) && recoveryMatches
    }

    @Suppress("ReturnCount") // Optional queue, missing row, and exact-match receipt are distinct decisions.
    private fun queueMatches(command: TurnAdmissionCommand): Boolean {
        val queue = command.queuedInput ?: return true
        val input = storage.sessionInputs.get(queue.inputId) ?: return false
        val identityMatches =
            input.sessionId == command.identity.sessionId &&
                input.revision == queue.revision && queue.inputId == command.identity.clientRequestId
        val config = input.configuration
        val configuration =
            com.helix.core.agent.turn.TurnSteeringConfiguration(
                config.providerId,
                config.modelId,
                config.mode,
                config.fingerprint,
            )
        val headMatches =
            !queue.requireHead ||
                storage.sessionInputs.headQueue(command.identity.sessionId)?.inputId == queue.inputId
        return identityMatches && input.state == SessionInputState.PENDING &&
            configuration == queue.configuration && headMatches
    }

    private fun resolveGoal(command: TurnAdmissionCommand): String? {
        val ownership = RoomGoalOwnership(storage)
        val goalId =
            when (val intent = command.goal) {
                TurnGoalIntent.None -> {
                    null
                }

                is TurnGoalIntent.Explicit -> {
                    intent.goalId
                }

                is TurnGoalIntent.ReuseOrCreate -> {
                    ownership
                        .current(command.identity.sessionId)
                        ?.takeIf {
                            it.state !in setOf("COMPLETED", "FAILED", "CANCELLED")
                        }?.id ?: ownership.unfinishedForSession(command.identity.sessionId)
                        ?: GoalRunCoordinator(storage, clock, idGenerator).create(
                            intent.objective,
                            intent.criteria,
                            intent.budgets,
                        )
                }
            }
        if (goalId != null) ownership.bind(goalId, command.identity.sessionId)
        return goalId
    }

    private fun TurnAdmissionCommand.toStartSpec(): TurnStartSpec =
        TurnStartSpec(
            sessionId = identity.sessionId,
            turnId = identity.turnId,
            firstModelCallId = identity.firstModelCallId,
            providerSnapshot = provider.snapshot,
            userText = input.text,
            attachments =
                input.attachments.map {
                    MessageAttachmentRepository.Binding(it.artifactId, it.purpose, it.boundSha256)
                },
            references =
                input.references.map { reference ->
                    ConversationReferenceSnapshotInput(
                        reference.sourceSessionId,
                        reference.sourceSessionTitle,
                        ConversationReferenceKind.valueOf(reference.selectionKind),
                        reference.sourceMessageIds,
                        reference.content,
                    ).also {
                        require(it.contentSha256 == reference.contentSha256) {
                            "reference snapshot hash mismatch"
                        }
                    }
                },
            clientRequestId = identity.clientRequestId,
            inputFingerprint = identity.inputFingerprint,
            revisedMessageId = (history as? TurnHistoryMutation.Revise)?.messageId,
            inputRevision = queuedInput?.revision,
            regenerateMessageId = (history as? TurnHistoryMutation.Regenerate)?.messageId,
            recoveryFromTurnId = recovery?.parentTurnId,
        )

    private fun validateInspection(
        spec: TurnStartSpec,
        control: RunControlConfig,
        goalId: String?,
    ) {
        require(control.mode == AgentMode.PLAN && spec.recoveryFromTurnId != null)
        val parent = storage.turns.resolve(requireNotNull(spec.recoveryFromTurnId))
        require(spec.clientRequestId == AutomaticRecoveryPolicy.requestId(parent.id))
        require(AutomaticRecoveryPolicy.eligible(parent))
        require(
            storage.turns
                .listBySession(spec.sessionId)
                .lastOrNull()
                ?.id == parent.id,
        )
        val originalGoal =
            storage.goalTurnBindings.byTurn(parent.id)?.let {
                storage.goalRuns.resolve(it.runId).goalId
            }
        require(goalId == originalGoal) { "RECOVERY_GOAL_BINDING_MISMATCH" }
    }

    private fun validateRecoveryPredecessor(spec: TurnStartSpec) {
        val predecessorId = spec.recoveryFromTurnId ?: return
        require(predecessorId != spec.turnId) { "RECOVERY_PREDECESSOR_SELF" }
        val predecessor =
            requireNotNull(storage.turns.find(predecessorId)) {
                "RECOVERY_PREDECESSOR_NOT_FOUND"
            }
        require(predecessor.sessionId == spec.sessionId) { "RECOVERY_PREDECESSOR_SESSION_MISMATCH" }
        val state = TurnState.valueOf(predecessor.state)
        require(state in setOf(TurnState.INTERRUPTED, TurnState.NEEDS_REVIEW)) {
            "RECOVERY_PREDECESSOR_STATE_INVALID: $state"
        }
    }

    private fun receipt(spec: TurnStartSpec): SubmitReceiptDecision {
        val requestId = spec.clientRequestId
        val fingerprint = spec.inputFingerprint
        return if (requestId == null || fingerprint == null) {
            SubmitReceiptDecision.Fresh
        } else {
            receipt(requestId, spec.sessionId, fingerprint)
        }
    }

    /**
     * REVISE/REGENERATE is an admission concern, not a TurnRoundCoordinator concern. The caller's outer
     * Room transaction commits this mutation together with Goal/Turn/ModelCall/runtime creation.
     */
    private fun applyHistoryMutation(
        spec: TurnStartSpec,
        goalId: String?,
    ) {
        spec.revisedMessageId?.let { messageId ->
            require(spec.userText != null && goalId == null) { "REVISION_REQUIRES_PLAIN_USER_TURN" }
            storage.messages.reviseLatest(spec.sessionId, messageId, requireNotNull(spec.clientRequestId))
        }
        spec.regenerateMessageId?.let { messageId ->
            require(spec.userText == null) { "REGENERATE_USER_TEXT_NOT_ALLOWED" }
            require(spec.revisedMessageId == null) { "CANNOT_REVISE_AND_REGENERATE" }
            storage.messages.regenerateLatest(spec.sessionId, messageId, requireNotNull(spec.clientRequestId))
        }
    }

    private fun submitReceiptDecision(
        existing: TurnEntity?,
        sessionId: String,
        fingerprint: String,
    ): SubmitReceiptDecision = SubmitReceipt.decide(existing, sessionId, fingerprint)
}

/** Pure request-receipt rule shared by Engine admission and JVM contract tests. */
internal object SubmitReceipt {
    fun decide(
        existing: TurnEntity?,
        incomingSessionId: String,
        incomingFingerprint: String,
    ): SubmitReceiptDecision =
        when {
            existing == null -> {
                SubmitReceiptDecision.Fresh
            }

            existing.sessionId == incomingSessionId && existing.inputFingerprint == incomingFingerprint -> {
                SubmitReceiptDecision.Deduplicated(existing.id)
            }

            else -> {
                SubmitReceiptDecision.Conflict
            }
        }
}

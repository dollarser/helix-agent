package com.helix.app.application.agent

import com.helix.app.application.context.PromptTemplateSource
import com.helix.app.infrastructure.agent.TurnRuntimeSnapshot
import com.helix.app.provider.ProviderService
import com.helix.app.provider.ProviderTurnSnapshot
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.core.agent.AgentLoop
import com.helix.core.agent.ConversationReferenceIntent
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.agent.runtime.TurnExecutionHooks
import com.helix.core.agent.runtime.TurnExecutionRequest
import com.helix.core.agent.runtime.TurnLoopRunner
import com.helix.core.agent.turn.QueuedAdmissionExpectation
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnAdmissionIdentity
import com.helix.core.agent.turn.TurnAdmissionInput
import com.helix.core.agent.turn.TurnGoalIntent
import com.helix.core.agent.turn.TurnHistoryMutation
import com.helix.core.agent.turn.TurnInputAttachment
import com.helix.core.agent.turn.TurnModelBinding
import com.helix.core.agent.turn.TurnRecoveryAdmission
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.agent.turn.TurnSteeringConfiguration
import com.helix.core.model.AgentMode
import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ConversationReferenceKind
import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Single application admission owner for user intents, accepted queue delivery and bounded recovery
 * inspection. All routes converge on the same Engine/Room transaction and original execution gate.
 * It owns no page selection, localized text, second worker scope or live execution registry.
 */
@Suppress("LongParameterList", "TooManyFunctions") // Existing application owners, not parallel lifecycle state.
internal class TurnSubmissionCommandHandler(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val providerService: ProviderService,
    private val sessionRunControls: SessionRunControlStore,
    private val turnEngine: TurnEngine,
    private val goalContinuation: GoalContinuationDriver,
    private val goalUserRequests: MutableMap<String, List<GoalUserRequest>>,
    private val workScope: CoroutineScope,
    private val agentLoop: TurnLoopRunner,
    private val executionHooks: (String) -> TurnExecutionHooks,
    private val inspectOriginal: suspend (String) -> String,
    private val promptTemplates: PromptTemplateSource,
) : TurnSubmissionCommands {
    private val turnGate = turnEngine.executionGate

    override suspend fun submit(command: SubmitTurnCommand): TurnSubmissionResult =
        launchTurn(
            text = command.text,
            providerId = command.providerId.value,
            retryTurnId = command.retryTurnId?.value,
            goalId = command.goalId?.value,
            attachmentBindings =
                command.attachments.map {
                    MessageAttachmentRepository.Binding(
                        artifactId = it.artifactId,
                        purpose = AttachmentPurpose.REFERENCE,
                        boundSha256 = it.boundSha256,
                    )
                },
            referenceSnapshots =
                command.references.map { reference ->
                    ConversationReferenceSnapshotInput(
                        sourceSessionId = reference.sourceSessionId,
                        sourceSessionTitle = reference.sourceSessionTitle,
                        selectionKind = ConversationReferenceKind.valueOf(reference.selectionKind),
                        sourceMessageIds = reference.sourceMessageIds,
                        content = reference.content,
                    ).also { snapshot ->
                        require(snapshot.contentSha256 == reference.contentSha256) {
                            "reference snapshot hash mismatch"
                        }
                    }
                },
            requestedSessionId = command.session.value,
            controlOverride = command.runControl(),
            clientRequestId = command.clientRequestId,
            continuousGoal = command.continuousGoal,
            continuation = command.goalContinuation,
            directUserRequest = command.directUserRequest,
            revisedMessageId = command.revisedMessageId,
            regenerateMessageId = command.regenerateMessageId,
        )

    /** Refuse unavailable original bindings; failed executor reconciliation remains UNKNOWN. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    suspend fun inspectRecovery(
        parentId: String,
        snapshot: TurnRuntimeSnapshot,
    ): TurnSubmissionResult {
        val parent = storage.turns.resolve(parentId)
        val session = storage.sessions.resolve(parent.sessionId)
        if (session.archivedAt != null || turnEngine.liveExecution.hasActive(session.id)) {
            return blocked(TurnSubmissionBlock.RECOVERY_UNAVAILABLE)
        }
        if (session.providerId != snapshot.providerId || session.modelId != snapshot.modelId) {
            return blocked(TurnSubmissionBlock.RECOVERY_UNAVAILABLE)
        }
        if (providerSnapshot(snapshot.providerId, snapshot.modelId) != snapshot.providerSnapshot) {
            return blocked(TurnSubmissionBlock.RECOVERY_UNAVAILABLE)
        }
        val executorObservation =
            try {
                inspectOriginal(parentId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "Original executor reconciliation unavailable; effects remain unknown."
            }
        val goalId =
            storage.goalTurnBindings.byTurn(parentId)?.let {
                storage.goalRuns.resolve(it.runId).goalId
            }
        val limits =
            com.helix.app.infrastructure.agent.AutomaticRecoveryPolicy
                .limits(snapshot) ?: return blocked(TurnSubmissionBlock.RECOVERY_UNAVAILABLE)
        return launchTurn(
            text = promptTemplates.text("automatic-recovery").trim() + "\n\n" + executorObservation,
            providerId = snapshot.providerId,
            goalId = goalId,
            requestedSessionId = parent.sessionId,
            controlOverride = snapshot.control.copy(mode = AgentMode.PLAN, budgets = limits),
            clientRequestId =
                com.helix.app.infrastructure.agent.AutomaticRecoveryPolicy
                    .requestId(parentId),
            recoveryParent = parentId,
        )
    }

    /** Trusted delivery preparation passes identity/version, never a caller-controlled Room row. */
    suspend fun deliverQueued(
        inputId: String,
        expectedRevision: Long,
        content: String,
        bindings: List<MessageAttachmentRepository.Binding>,
        control: RunControlConfig,
        requireHead: Boolean,
    ): TurnSubmissionResult {
        val input =
            storage.sessionInputs
                .get(inputId)
                ?.takeIf { it.revision == expectedRevision && it.state == SessionInputState.PENDING }
                ?: return blocked(TurnSubmissionBlock.INPUT_NO_LONGER_CONSUMABLE)
        return launchTurn(
            text = content,
            providerId = input.configuration.providerId,
            attachmentBindings = bindings,
            referenceSnapshots = listOfNotNull(storage.sessionInputs.readReference(input)),
            requestedSessionId = input.sessionId,
            controlOverride = control,
            clientRequestId = input.inputId,
            continuousGoal = input.configuration.mode == AgentMode.GOAL.name,
            queuedInput = input,
            requireQueueHead = requireHead,
        )
    }

    fun revokeGoalIntent(sessionId: String) {
        goalContinuation.disarm(sessionId)
        goalUserRequests.entries.removeAll { entry -> entry.value.any { it.sessionId == sessionId } }
    }

    fun queueStillConsumable(
        input: SessionInputRecord,
        requireHead: Boolean,
    ): Boolean {
        val current = storage.sessionInputs.get(input.inputId) ?: return false
        val headMatches = !requireHead || storage.sessionInputs.headQueue(input.sessionId)?.inputId == input.inputId
        val unsettled = storage.turns.admissionBlockers(input.sessionId).isNotEmpty()
        val sameRevision = current.revision == input.revision
        return current.state == SessionInputState.PENDING && sameRevision && headMatches && !unsettled
    }

    private suspend fun providerSnapshot(
        providerId: String,
        modelId: String?,
    ): String = ProviderTurnSnapshot.capture(providerService, providerId, modelId)

    private fun blocked(reason: TurnSubmissionBlock): TurnSubmissionResult.Blocked =
        TurnSubmissionResult.Blocked(reason)

    // one fail-closed return per guard (session, snapshot, turn gate); one branch per guard plus
    // the idempotency dedup check (HX2-01 §2e)
    // One atomic admission/activation transaction.
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod", "SwallowedException")
    private suspend fun launchTurn(
        text: String?,
        providerId: String,
        retryTurnId: String? = null,
        attachmentBindings: List<MessageAttachmentRepository.Binding> = emptyList(),
        referenceSnapshots: List<ConversationReferenceSnapshotInput> = emptyList(),
        goalId: String? = null,
        requestedSessionId: String,
        controlOverride: RunControlConfig? = null,
        clientRequestId: String,
        continuousGoal: Boolean = false,
        continuation: com.helix.core.agent.GoalContinuationRequest? = null,
        directUserRequest: Boolean = false,
        revisedMessageId: String? = null,
        queuedInput: SessionInputRecord? = null,
        requireQueueHead: Boolean = true,
        regenerateMessageId: String? = null,
        recoveryParent: String? = null,
    ): TurnSubmissionResult {
        val session =
            storage.sessions
                .find(requestedSessionId)
                ?.takeIf { it.archivedAt == null }
                ?: return blocked(TurnSubmissionBlock.SESSION_UNAVAILABLE)
        val sessionId = session.id
        val expectedBinding = TurnSessionAdmissionBinding(providerId, session.modelId)
        // Snapshot before creating the durable Turn: later UI/profile changes cannot alter this
        // Turn's mode, tool table, dispatcher mode, or limits.
        val selectedControl = sessionRunControls.ensure(sessionId, clock.now().toEpochMilli())
        val control = controlOverride ?: selectedControl
        val sourceText =
            text ?: retryTurnId?.let { sourceTurn ->
                storage.messages
                    .listBySession(sessionId)
                    .lastOrNull { it.turnId == sourceTurn && it.role == "USER" }
                    ?.let {
                        com.helix.app.agent.ContextHistory
                            .read(storage, it)
                    }
            }
        val manualCompaction = sourceText == com.helix.core.agent.ContextCommands.COMPACT
        // The Room read runs OUTSIDE the gate: a suspend point must never be reached while holding the monitor.
        val providerConfig =
            try {
                providerService.storedConfig(providerId)
            } catch (_: IllegalArgumentException) {
                // The provider row was deleted or is corrupt between the gate
                // and the snapshot (storedConfig throws IAE for both): no turn
                // row has been written yet, so surface a blocked state — the
                // send must never vanish.
                return blocked(TurnSubmissionBlock.PROVIDER_CHANGED)
            }
        val snapshot = ProviderTurnSnapshot.encode(providerConfig, session.modelId)
        val admittedModelId = session.modelId ?: providerConfig.model
        synchronized(turnGate) {
            if (queuedInput != null &&
                !SessionInputConfiguration.matches(
                    queuedInput,
                    snapshot,
                    control,
                    selectedControl.mode.name,
                )
            ) {
                storage.sessionInputs.markNeedsAttention(
                    queuedInput.inputId,
                    queuedInput.revision,
                    "INPUT_CONFIGURATION_CHANGED",
                    clock.now().toEpochMilli(),
                )
                return blocked(TurnSubmissionBlock.INPUT_CONFIGURATION_CHANGED)
            }
            if (continuation != null && storage.sessionInputs.headQueue(sessionId) != null) {
                return blocked(TurnSubmissionBlock.CONTINUATION_DEFERRED)
            }
            if (continuation != null && !goalContinuation.admits(sessionId, goalId, continuation, snapshot)) {
                return blocked(TurnSubmissionBlock.CONTINUATION_REFUSED)
            }
            val canRecover =
                revisedMessageId == null &&
                    regenerateMessageId == null &&
                    retryTurnId == null &&
                    continuation == null &&
                    (queuedInput != null || directUserRequest)
            val recoveryFromTurnId =
                if (recoveryParent != null) {
                    recoveryParent
                } else if (canRecover) {
                    turnEngine.recoveryPredecessor(sessionId, clientRequestId)
                } else {
                    null
                }
            val inputFingerprint =
                TurnInputFingerprint.of(
                    text,
                    attachmentBindings,
                    references = referenceSnapshots,
                    revisedMessageId = revisedMessageId,
                    regenerateMessageId = regenerateMessageId,
                    recoveryFromTurnId = recoveryFromTurnId,
                )
            // Persistent submit-dedup (research doc section 34): a re-drive with the same session +
            // input returns the started turn; a diverged session or input is a conflict (refused).
            when (val receipt = turnEngine.submissionReceipt(clientRequestId, sessionId, inputFingerprint)) {
                is com.helix.core.agent.turn.SubmitReceiptDecision.Deduplicated -> {
                    return TurnSubmissionResult.Started(receipt.turnId, deduplicated = true)
                }

                com.helix.core.agent.turn.SubmitReceiptDecision.Conflict -> {
                    return blocked(TurnSubmissionBlock.RECEIPT_CONFLICT)
                }

                com.helix.core.agent.turn.SubmitReceiptDecision.Fresh -> {
                    Unit
                }
            }
            // Durable Room truth blocks only genuinely live non-terminal Turns. NEEDS_REVIEW is
            // execution-stopped history; successor admission is allowed and effects are gated later.
            turnEngine.sessionBlocker(sessionId)?.let {
                return blocked(TurnSubmissionBlock.SESSION_BUSY)
            }
            // The process-local registry remains a same-process race guard until E1-B2 moves Jobs.
            if (turnEngine.liveExecution.hasActive(sessionId)) {
                return blocked(TurnSubmissionBlock.SESSION_BUSY)
            }
            if (queuedInput != null && !queueStillConsumable(queuedInput, requireQueueHead)) {
                return blocked(TurnSubmissionBlock.INPUT_NO_LONGER_CONSUMABLE)
            }
            val isGoalOrRetry =
                goalId != null || continuousGoal || control.mode == AgentMode.GOAL || retryTurnId != null
            if (revisedMessageId != null && isGoalOrRetry) {
                return blocked(TurnSubmissionBlock.INVALID_HISTORY_MUTATION)
            }
            if (regenerateMessageId != null && (revisedMessageId != null || text != null)) {
                return blocked(TurnSubmissionBlock.INVALID_HISTORY_MUTATION)
            }
            val liveSession = storage.sessions.resolve(sessionId)
            if (liveSession.archivedAt != null) return blocked(TurnSubmissionBlock.SESSION_UNAVAILABLE)
            if (!expectedBinding.matches(liveSession)) {
                return blocked(TurnSubmissionBlock.PROVIDER_CHANGED)
            }
            val turnId = idGenerator()
            val callId = idGenerator()
            val admissionCommand =
                TurnAdmissionCommand(
                    identity =
                        TurnAdmissionIdentity(
                            sessionId,
                            turnId,
                            callId,
                            clientRequestId,
                            inputFingerprint,
                        ),
                    provider = TurnModelBinding(providerId, admittedModelId, snapshot),
                    input =
                        TurnAdmissionInput(
                            text,
                            attachmentBindings.map {
                                TurnInputAttachment(it.artifactId, it.purpose, it.boundSha256)
                            },
                            referenceSnapshots.map { reference ->
                                ConversationReferenceIntent(
                                    reference.sourceSessionId,
                                    reference.sourceSessionTitle,
                                    reference.selectionKind.name,
                                    reference.sourceMessageIds,
                                    reference.content,
                                    reference.contentSha256,
                                )
                            },
                        ),
                    control =
                        if (manualCompaction) {
                            control.copy(mode = AgentMode.CHAT, chatToolsEnabled = false)
                        } else {
                            control
                        },
                    wakeReason =
                        if (continuation == null) {
                            com.helix.core.agent.GoalWakeReason.USER_OPEN
                        } else {
                            com.helix.core.agent.GoalWakeReason.FOREGROUND_CONTINUATION
                        },
                    expectedSessionModelId = session.modelId,
                    queuedInput =
                        queuedInput?.let { accepted ->
                            QueuedAdmissionExpectation(
                                accepted.inputId,
                                accepted.revision,
                                requireQueueHead,
                                accepted.configuration.let {
                                    TurnSteeringConfiguration(
                                        it.providerId,
                                        it.modelId,
                                        it.mode,
                                        it.fingerprint,
                                    )
                                },
                            )
                        },
                    history =
                        when {
                            revisedMessageId != null -> TurnHistoryMutation.Revise(revisedMessageId)
                            regenerateMessageId != null -> TurnHistoryMutation.Regenerate(regenerateMessageId)
                            else -> TurnHistoryMutation.None
                        },
                    recovery =
                        recoveryFromTurnId?.let {
                            TurnRecoveryAdmission(it, inspection = recoveryParent != null)
                        },
                    goal =
                        when {
                            manualCompaction -> {
                                TurnGoalIntent.None
                            }

                            goalId != null -> {
                                TurnGoalIntent.Explicit(goalId)
                            }

                            control.mode == AgentMode.GOAL && !text.isNullOrBlank() -> {
                                TurnGoalIntent.ReuseOrCreate(text, emptyList(), control.goalBudgets)
                            }

                            else -> {
                                TurnGoalIntent.None
                            }
                        },
                )
            val admission = turnEngine.admit(admissionCommand)
            val started =
                when (admission) {
                    is com.helix.core.agent.turn.TurnAdmissionResult.Started -> {
                        admission.turn
                    }

                    is com.helix.core.agent.turn.TurnAdmissionResult.Deduplicated -> {
                        return TurnSubmissionResult.Started(admission.turnId, deduplicated = true)
                    }

                    is com.helix.core.agent.turn.TurnAdmissionResult.Blocked -> {
                        return blocked(TurnSubmissionBlock.SESSION_BUSY)
                    }

                    com.helix.core.agent.turn.TurnAdmissionResult.Conflict -> {
                        return blocked(TurnSubmissionBlock.RECEIPT_CONFLICT)
                    }

                    com.helix.core.agent.turn.TurnAdmissionResult.FreshRejected -> {
                        return blocked(TurnSubmissionBlock.FRESH_GUARD_REJECTED)
                    }

                    com.helix.core.agent.turn.TurnAdmissionResult.GoalRefused -> {
                        return blocked(TurnSubmissionBlock.GOAL_REFUSED)
                    }
                }
            if (manualCompaction || revisedMessageId != null) revokeGoalIntent(sessionId)
            val coordinator = started.coordinator
            val effectiveControl = started.control
            val effectiveGoalId = started.goalId
            val canAuthorizeGoal = !manualCompaction && directUserRequest && queuedInput == null
            if (canAuthorizeGoal && !text.isNullOrBlank()) {
                goalUserRequests[turnId] = listOf(GoalUserRequest(sessionId, text, providerId, control, snapshot))
            }
            if (continuousGoal && effectiveGoalId != null) {
                goalContinuation.started(
                    sessionId,
                    effectiveGoalId,
                    providerId,
                    control,
                    turnId,
                    continuation,
                    snapshot,
                )
            }
            return TurnSubmissionResult.Started(
                launchExecution(sessionId, coordinator, providerId, retryTurnId, effectiveControl),
            )
        }
    }

    /** The Engine owns the worker/start gate/AgentLoop lifecycle; Chat supplies only app projections. */
    private fun launchExecution(
        sessionId: String,
        coordinator: TurnRoundCoordinator,
        providerId: String,
        retryTurnId: String?,
        effectiveControl: RunControlConfig,
        entry: AgentLoop.Entry = AgentLoop.Entry.INITIAL,
    ): String =
        turnEngine.launchExecution(
            scope = workScope,
            loop = agentLoop,
            request =
                TurnExecutionRequest(
                    sessionId = sessionId,
                    coordinator = coordinator,
                    providerId = providerId,
                    retryTurnId = retryTurnId,
                    control = effectiveControl,
                    entry = entry,
                ),
            hooks = executionHooks(sessionId),
        )
}

/** Frozen preparation identity, rechecked from Room under admission's transaction. */
internal data class TurnSessionAdmissionBinding(
    val providerId: String,
    val modelId: String?,
) {
    fun matches(current: com.helix.core.storage.entity.SessionEntity): Boolean =
        current.archivedAt == null && current.providerId == providerId && current.modelId == modelId
}

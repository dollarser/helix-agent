package com.helix.app.infrastructure.agent

import com.helix.app.agent.AssistantToolResponseContent
import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextCompaction
import com.helix.app.agent.TurnStartSpec
import com.helix.app.agent.promptSnapshotRecord
import com.helix.app.infrastructure.agent.TurnSettlement
import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.TurnTerminalCheckpoint
import com.helix.core.agent.turn.ResponseCommitReceipt
import com.helix.core.agent.turn.ToolResultsCommitReceipt
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.agent.turn.TurnRoundStore
import com.helix.core.agent.turn.TurnSteeringInput
import com.helix.core.model.Clock
import com.helix.core.model.ModelRole
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ConversationReferenceSnapshotInput
import com.helix.core.storage.repository.MessageAttachmentRepository
import kotlinx.coroutines.CancellationException

/** Direct Room transaction adapter; owns no process-local round state. */
@Suppress("TooManyFunctions") // One local atomic round journal, without another runtime owner.
internal class RoomTurnRoundStore(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val sessionId: String,
    private val turnId: String,
    private val providerSnapshot: String,
) : TurnRoundStore {
    private val settlement = TurnSettlement(storage, clock, idGenerator)

    override fun retryEmptyModelStream(
        checkpoint: TurnTerminalCheckpoint,
        nextModelCallId: String,
    ) {
        val current = checkpoint
        storage.withTransaction {
            val turn = requireNotCancelling()
            storage.modelCalls.update(
                storage.modelCalls.resolve(current.modelCallId),
                "FAILED",
                checkpoint.usageJson,
                null,
            )
            val building =
                storage.turns.updateState(
                    turn,
                    TurnState.BUILDING_CONTEXT,
                    current.modelStep + 1,
                    null,
                    null,
                )
            storage.modelCalls.append(nextModelCallId, turnId, providerSnapshot, CALL_RUNNING)
            storage.turns.updateState(building, TurnState.WAITING_MODEL, current.modelStep + 1, null, null)
        }
    }

    override fun recordDiagnostic(
        modelCallId: String,
        type: String,
        payload: String,
    ) {
        if (type == "context.job_observations") {
            require(payload.length <= 1024)
            val refs =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(payload)
            require(refs is kotlinx.serialization.json.JsonArray && refs.size <= 8)
            require(
                refs.all { ref ->
                    ref is kotlinx.serialization.json.JsonPrimitive && ref.isString &&
                        Regex("audit:job-observed-[a-f0-9]{64}").matches(ref.content)
                },
            )
        } else {
            require(
                type in
                    setOf(
                        "budget.request",
                        "budget.admitted",
                        "budget.result",
                        "context.compaction",
                        com.helix.core.agent.ModelRecoveryPolicy.DIAGNOSTIC_TYPE,
                    ),
            )
            require(payload.length <= 512)
        }
        storage.auditEvents.append(
            idGenerator(),
            modelCallId,
            type,
            "agent",
            payload,
            clock.now().toEpochMilli(),
        )
    }

    override fun recordPromptSnapshot(
        modelCallId: String,
        snapshot: PromptSnapshot?,
        summary: Boolean,
    ) {
        if (snapshot == null) return
        val record = promptSnapshotRecord(snapshot, modelCallId, turnId, compacting = summary) ?: return
        storage.withTransaction {
            storage.modelCalls.recordPrompt(record.modelCallId, record.fingerprint, record.sectionsJson)
            storage.auditEvents.append(
                idGenerator(),
                record.modelCallId,
                AUDIT_PROMPT_ASSEMBLED,
                "assembler",
                record.auditPayload,
                clock.now().toEpochMilli(),
            )
        }
    }

    override fun recordRequestManifest(
        modelCallId: String,
        manifestJson: String?,
    ) {
        if (manifestJson.isNullOrBlank()) return
        storage.withTransaction {
            storage.modelCalls.recordRequestManifest(modelCallId, manifestJson)
        }
    }

    override fun beginModelStream(checkpoint: TurnTerminalCheckpoint) =
        transitionPersisted(TurnState.RECEIVING_MODEL, checkpoint.modelStep)

    override fun beginToolBatch(checkpoint: TurnTerminalCheckpoint) =
        transitionPersisted(TurnState.RUNNING_TOOL, checkpoint.modelStep)

    override fun commitModelToolStep(
        checkpoint: TurnTerminalCheckpoint,
        toolCallsJson: String,
    ) {
        val current = checkpoint
        require(current.phase == TurnState.RUNNING_TOOL)
        val text = checkpoint.assistantText
        val response = AssistantToolResponseContent.attachText(toolCallsJson, text)
        storage.withTransaction {
            requireNotCancelling()
            storage.modelCalls.update(
                storage.modelCalls.resolve(current.modelCallId),
                CALL_COMPLETED,
                checkpoint.usageJson,
                null,
            )
            if (text.isNotBlank()) {
                storage.messages.append(
                    idGenerator(),
                    sessionId,
                    turnId,
                    ModelRole.ASSISTANT.name,
                    ChatHistoryBuilder.KIND_TOOL_PROGRESS,
                    text,
                )
            }
            storage.messages
                .append(
                    idGenerator(),
                    sessionId,
                    turnId,
                    ModelRole.ASSISTANT.name,
                    ChatHistoryBuilder.KIND_TOOL_CALLS,
                    response,
                ).also { storage.modelCalls.bindToolMessage(current.modelCallId, it.id) }
        }
    }

    override fun openNextModelCall(
        checkpoint: TurnTerminalCheckpoint,
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    ): ToolResultsCommitReceipt {
        val current = checkpoint
        var cancelled = false
        storage.withTransaction {
            var turn = storage.turns.resolve(turnId)
            cancelled = turn.state == TurnState.CANCELLING.name
            if (!cancelled) {
                turn = storage.turns.updateState(turn, TurnState.RECORDING_TOOL_RESULT, current.modelStep, null, null)
            }
            messages.forEach { message ->
                val messageId = idGenerator()
                storage.messages.append(
                    messageId,
                    sessionId,
                    turnId,
                    message.role.name,
                    message.kind,
                    message.content,
                )
                message.visualArtifact?.let { image ->
                    check(message.role == ModelRole.TOOL) { "Visual observation must belong to a tool result" }
                    val artifact = storage.artifacts.resolve(image.artifactId)
                    check(
                        artifact.sessionId == sessionId && artifact.turnId == turnId && artifact.sha256 == image.sha256,
                    ) {
                        "Tool visual artifact ownership mismatch"
                    }
                    storage.messageAttachments.bind(
                        messageId,
                        listOf(
                            MessageAttachmentRepository.Binding(
                                image.artifactId,
                                com.helix.core.model.AttachmentPurpose.TOOL_OBSERVATION,
                                image.sha256,
                            ),
                        ),
                    )
                }
            }
            if (!cancelled) {
                turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, current.modelStep + 1, null, null)
                storage.modelCalls.append(nextModelCallId, turnId, providerSnapshot, CALL_RUNNING)
                storage.turns.updateState(turn, TurnState.WAITING_MODEL, current.modelStep + 1, null, null)
            }
        }
        return if (cancelled) {
            ToolResultsCommitReceipt.RECORDED_CANCELLED
        } else {
            ToolResultsCommitReceipt.NEXT_MODEL_OPENED
        }
    }

    override fun appendSteeringBeforeRequest(
        checkpoint: TurnTerminalCheckpoint,
        draft: TurnSteeringInput,
    ): Boolean {
        var appended = false
        storage.withTransaction {
            requireNotCancelling()
            if (currentSteeringMatches(draft)) {
                appendSteering(draft)
                appended = true
            }
        }
        return appended
    }

    override fun completeResponse(
        checkpoint: TurnTerminalCheckpoint,
        prepared: TurnSteeringInput?,
        nextModelCallId: String,
    ): ResponseCommitReceipt {
        val current = checkpoint
        var boundary = ResponseInputBoundary.TERMINAL
        var committedTerminal: ModelStreamTerminal? = null
        storage.withTransaction {
            val turn = storage.turns.resolve(turnId)
            val head = storage.sessionInputs.headSteer(sessionId, turnId)
            val pending = head?.state == com.helix.core.storage.repository.SessionInputState.PENDING
            if (turn.state == TurnState.CANCELLING.name) {
                // The host owns stop-reason settlement. Do not preempt its durable error code.
                boundary = ResponseInputBoundary.CANCELLED
            } else if (pending) {
                if (prepared == null || !currentSteeringMatches(prepared)) {
                    boundary = ResponseInputBoundary.RECHECK
                } else {
                    val stream = checkpoint
                    if (!current.modelCallClosed && stream.assistantText.isNotBlank()) {
                        storage.messages.append(
                            idGenerator(),
                            sessionId,
                            turnId,
                            ModelRole.ASSISTANT.name,
                            ChatHistoryBuilder.KIND_TEXT,
                            stream.assistantText,
                        )
                    }
                    if (!current.modelCallClosed) {
                        storage.modelCalls.update(
                            storage.modelCalls.resolve(current.modelCallId),
                            CALL_COMPLETED,
                            stream.usageJson,
                            null,
                        )
                    }
                    appendSteering(prepared)
                    val building =
                        storage.turns.updateState(
                            turn,
                            TurnState.BUILDING_CONTEXT,
                            current.modelStep + 1,
                            null,
                            null,
                        )
                    storage.modelCalls.append(nextModelCallId, turnId, providerSnapshot, CALL_RUNNING)
                    storage.turns.updateState(building, TurnState.WAITING_MODEL, current.modelStep + 1, null, null)
                    boundary = ResponseInputBoundary.CONTINUED
                }
            } else {
                committedTerminal =
                    settlement.settleInTransaction(
                        checkpoint,
                        ModelStreamTerminal(TurnState.COMPLETED, null),
                    )
            }
        }
        return ResponseCommitReceipt(boundary, committedTerminal)
    }

    private fun currentSteeringMatches(draft: TurnSteeringInput): Boolean {
        val current = storage.sessionInputs.headSteer(sessionId, turnId) ?: return false
        return current.state == com.helix.core.storage.repository.SessionInputState.PENDING &&
            current.inputId == draft.inputId && current.revision == draft.revision &&
            current.configuration.providerId == draft.configuration.providerId &&
            current.configuration.modelId == draft.configuration.modelId &&
            current.configuration.mode == draft.configuration.mode &&
            current.configuration.fingerprint == draft.configuration.fingerprint && current.expectedTurnId == turnId
    }

    private fun appendSteering(draft: TurnSteeringInput) {
        val message =
            storage.messages.append(
                idGenerator(),
                sessionId,
                turnId,
                ModelRole.USER.name,
                ChatHistoryBuilder.KIND_TEXT,
                draft.content,
            )
        if (draft.attachments.isNotEmpty()) {
            val bindings =
                draft.attachments.map {
                    MessageAttachmentRepository.Binding(it.artifactId, it.purpose, it.boundSha256)
                }
            storage.messageAttachments.bind(message.id, bindings)
        }
        if (draft.references.isNotEmpty()) {
            storage.messageReferenceSnapshots.bind(
                message.id,
                draft.references.map { reference ->
                    ConversationReferenceSnapshotInput(
                        reference.sourceSessionId,
                        reference.sourceSessionTitle,
                        com.helix.core.storage.repository.ConversationReferenceKind
                            .valueOf(reference.selectionKind),
                        reference.sourceMessageIds,
                        reference.content,
                    ).also {
                        check(it.contentSha256 == reference.contentSha256)
                    }
                },
                clock.now().toEpochMilli(),
            )
        }
        check(
            storage.sessionInputs.markAppended(
                draft.inputId,
                draft.revision,
                turnId,
                message.id,
                clock.now().toEpochMilli(),
            ),
        ) { "Input consumption changed during its transaction" }
    }

    override fun recordInputRequestStarted(
        checkpoint: TurnTerminalCheckpoint,
        messageIds: Set<String>,
    ) {
        storage.withTransaction {
            requireNotCancelling()
            val callId = checkpoint.modelCallId
            storage.sessionInputs
                .appendedForMessages(turnId, messageIds)
                .filter {
                    it.requestModelCallId == null
                }.forEach {
                    check(storage.sessionInputs.markRequestStarted(it.inputId, callId, clock.now().toEpochMilli()))
                }
        }
    }

    override fun commitCompaction(
        checkpoint: TurnTerminalCheckpoint,
        plan: ContextCompactionPlan,
        nextModelCallId: String?,
        notice: String?,
        failureReason: String?,
        saveSummary: Boolean,
    ) {
        val current = checkpoint
        val stream = checkpoint
        storage.withTransaction {
            requireNotCancelling()
            if (failureReason == null && saveSummary) {
                ContextCompaction.persist(
                    storage,
                    sessionId,
                    turnId,
                    idGenerator(),
                    plan,
                    stream.assistantText,
                    current.modelCallId,
                )
            }
            if (notice != null && failureReason == null) {
                storage.messages.append(
                    idGenerator(),
                    sessionId,
                    turnId,
                    ModelRole.ASSISTANT.name,
                    ChatHistoryBuilder.KIND_TEXT,
                    notice,
                )
            }
            storage.modelCalls.update(
                storage.modelCalls.resolve(current.modelCallId),
                if (failureReason == null) "COMPLETED" else "FAILED",
                stream.usageJson,
                null,
            )
            if (nextModelCallId != null) {
                var turn = storage.turns.resolve(turnId)
                turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, current.modelStep + 1, null, null)
                storage.modelCalls.append(nextModelCallId, turnId, providerSnapshot, CALL_RUNNING)
                storage.turns.updateState(turn, TurnState.WAITING_MODEL, current.modelStep + 1, null, null)
            }
        }
    }

    private fun transitionPersisted(
        state: TurnState,
        step: Int,
    ) {
        storage.withTransaction {
            val turn = requireNotCancelling()
            storage.turns.updateState(turn, state, step, null, null)
        }
    }

    /** Call inside the same transaction as the write: the stop intent precedes its coroutine signal. */
    private fun requireNotCancelling(): com.helix.core.storage.entity.TurnEntity {
        val turn = storage.turns.resolve(turnId)
        if (turn.state == TurnState.CANCELLING.name) throw CancellationException("Turn cancellation was persisted")
        return turn
    }

    companion object {
        private const val CALL_RUNNING = "RUNNING"
        private const val CALL_COMPLETED = "COMPLETED"
        const val AUDIT_PROMPT_ASSEMBLED = "prompt.assembled"

        fun start(
            storage: HelixStorage,
            clock: Clock,
            idGenerator: () -> String,
            spec: TurnStartSpec,
        ): TurnRoundCoordinator {
            val now = clock.now().toEpochMilli()
            storage.withTransaction {
                // The receipt (clientRequestId + inputFingerprint) commits WITH the turn row (research
                // doc section 34): a restart can no longer let the same id re-start a second turn.
                var turn =
                    storage.turns.start(
                        spec.turnId,
                        spec.sessionId,
                        now,
                        spec.clientRequestId,
                        spec.inputFingerprint,
                        spec.recoveryFromTurnId,
                    )
                spec.goalRunId?.let { storage.goalTurnBindings.bind(spec.turnId, it) }
                turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, 0, null, null)
                if (spec.userText != null || spec.attachments.isNotEmpty() || spec.references.isNotEmpty()) {
                    appendInitialInput(storage, spec, idGenerator(), now)
                }
                storage.modelCalls.append(
                    spec.firstModelCallId,
                    spec.turnId,
                    spec.providerSnapshot,
                    CALL_RUNNING,
                )
                storage.turns.updateState(turn, TurnState.WAITING_MODEL, 1, null, null)
            }
            return TurnRoundCoordinator(
                spec.sessionId,
                spec.turnId,
                spec.firstModelCallId,
                RoomTurnRoundStore(storage, clock, idGenerator, spec.sessionId, spec.turnId, spec.providerSnapshot),
            )
        }

        /** Invoked inside the Turn creation transaction, including attachment-only submissions. */
        private fun appendInitialInput(
            storage: HelixStorage,
            spec: TurnStartSpec,
            messageId: String,
            now: Long,
        ) {
            val message =
                storage.messages.append(
                    messageId,
                    spec.sessionId,
                    spec.turnId,
                    ModelRole.USER.name,
                    ChatHistoryBuilder.KIND_TEXT,
                    spec.userText.orEmpty(),
                )
            if (spec.attachments.isNotEmpty()) storage.messageAttachments.bind(message.id, spec.attachments)
            if (spec.references.isNotEmpty()) storage.messageReferenceSnapshots.bind(message.id, spec.references, now)
            spec.inputRevision?.let { revision ->
                check(
                    storage.sessionInputs.markAppended(
                        requireNotNull(spec.clientRequestId),
                        revision,
                        spec.turnId,
                        message.id,
                        now,
                    ),
                ) { "Input changed before Turn creation" }
            }
        }
    }
}

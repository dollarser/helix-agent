package com.helix.core.agent.runtime

import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.TerminalCommitCommand
import com.helix.core.agent.TerminalCommitResult
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.TurnReviewCheckpoint
import com.helix.core.agent.TurnTerminalCheckpoint
import com.helix.core.agent.turn.AdmittedTurn
import com.helix.core.agent.turn.CancelTurnCommand
import com.helix.core.agent.turn.ResponseCommitReceipt
import com.helix.core.agent.turn.ReviewResolutionCommand
import com.helix.core.agent.turn.SessionTurnBlocker
import com.helix.core.agent.turn.SubmitReceiptDecision
import com.helix.core.agent.turn.ToolResultsCommitReceipt
import com.helix.core.agent.turn.TurnAdmissionCommand
import com.helix.core.agent.turn.TurnAdmissionIdentity
import com.helix.core.agent.turn.TurnAdmissionResult
import com.helix.core.agent.turn.TurnCancellationReceipt
import com.helix.core.agent.turn.TurnLifecycleSnapshot
import com.helix.core.agent.turn.TurnLifecycleStore
import com.helix.core.agent.turn.TurnRecoveryReport
import com.helix.core.agent.turn.TurnReviewResolutionResult
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.agent.turn.TurnRoundStore
import com.helix.core.agent.turn.TurnSteeringInput
import com.helix.core.agent.turn.TurnTerminalReleaseReceipt
import com.helix.core.model.AssistantToolCall
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallId
import com.helix.core.model.ToolName
import com.helix.core.model.TurnState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Closed in-memory persistence port; rejects stale identity and contradictory terminal writes. */
@Suppress("TooManyFunctions")
internal class LifecycleTestStore(
    private val events: MutableList<String>,
) : TurnLifecycleStore,
    TurnRoundStore {
    private var identity: TurnAdmissionIdentity? = null
    private var phase: TurnState? = null
    private var assistantText: String? = null
    private var terminal: ModelStreamTerminal? = null
    private var modelCallId: String? = null
    val log = mutableListOf<String>()
    val history = mutableListOf<ModelMessage>()
    val committedDrafts = mutableListOf<TurnMessageDraft>()
    val terminalCommands = mutableListOf<TerminalCommitCommand>()
    var rejectTerminal = false
    var failTerminal = false
    var terminalFailure: Exception? = null
    var failPreparation = false
    var deliveryParked = false
    var reviewCheckpoint: TurnReviewCheckpoint? = null
        private set

    override fun admit(command: TurnAdmissionCommand): TurnAdmissionResult {
        if (identity !=
            null
        ) {
            return TurnAdmissionResult.Blocked(requireNotNull(sessionBlocker(command.identity.sessionId)))
        }
        identity = command.identity
        modelCallId = command.identity.firstModelCallId
        history += ModelMessage(ModelRole.USER, requireNotNull(command.input.text))
        phase = TurnState.WAITING_MODEL
        return TurnAdmissionResult.Started(
            AdmittedTurn(
                TurnRoundCoordinator(
                    command.identity.sessionId,
                    command.identity.turnId,
                    command.identity.firstModelCallId,
                    this,
                ),
                command.control,
                null,
            ),
        )
    }

    @Suppress("ReturnCount") // Explicit fixture conflict, idempotency and fresh commit outcomes.
    override suspend fun commitTerminal(command: TerminalCommitCommand): TerminalCommitResult {
        terminalCommands += command
        terminalFailure?.let {
            events += "commit-threw"
            throw it
        }
        if (failTerminal) {
            events += "commit-threw"
            error("injected durable store unavailable")
        }
        val checkpoint = command.checkpoint
        if (!matches(checkpoint.turnId, checkpoint.sessionId) || rejectTerminal) {
            events += "commit-rejected"
            return TerminalCommitResult.Conflict("OWNER_CHANGED")
        }
        terminal?.let {
            return if (it == command.outcome) {
                TerminalCommitResult.AlreadyApplied(it)
            } else {
                TerminalCommitResult.Conflict("TERMINAL_CHANGED")
            }
        }
        check(checkpoint.modelCallId == modelCallId)
        terminal = command.outcome
        phase = command.outcome.state
        assistantText = checkpoint.assistantText
        events += "commit:${command.outcome.state}"
        return TerminalCommitResult.Applied(command.outcome)
    }

    override fun prepareTerminalRelease(
        turnId: String,
        sessionId: String,
        outcome: ModelStreamTerminal,
    ): TurnTerminalReleaseReceipt {
        check(matches(turnId, sessionId) && terminal == outcome && phase!!.isTerminal)
        events += "prepare"
        check(!failPreparation) { "injected post-commit failure" }
        return TurnTerminalReleaseReceipt()
    }

    override fun parkForReview(checkpoint: TurnReviewCheckpoint) {
        check(matches(checkpoint.turnId, checkpoint.sessionId) && terminal == null)
        check(phase == TurnState.RUNNING_TOOL || phase == TurnState.CANCELLING)
        reviewCheckpoint = checkpoint
        phase = TurnState.NEEDS_REVIEW
        events += "review"
    }

    override fun parkFailedTerminalDelivery(
        turnId: String,
        sessionId: String,
    ) {
        check(matches(turnId, sessionId) && terminal != null && phase!!.isTerminal)
        deliveryParked = true
        events += "terminal-delivery-park"
    }

    override fun parkUnknownDelivery(
        turnId: String,
        sessionId: String,
    ) {
        check(matches(turnId, sessionId) && terminal == null)
        deliveryParked = true
        events += "unknown"
    }

    @Suppress("ReturnCount") // Fixture mirrors distinct not-found, terminal, review and live receipts.
    override fun cancel(command: CancelTurnCommand): TurnCancellationReceipt {
        if (!matches(command.turnId, command.sessionId)) return TurnCancellationReceipt.NotFound
        val current = requireNotNull(phase)
        if (current.isTerminal) return TurnCancellationReceipt.AlreadyTerminal(current)
        if (current == TurnState.NEEDS_REVIEW) return TurnCancellationReceipt.ReviewRequired
        check(command.hasLiveDriver)
        phase = TurnState.CANCELLING
        return TurnCancellationReceipt.SignalLive
    }

    override fun beginModelStream(checkpoint: TurnTerminalCheckpoint) {
        check(matches(checkpoint.turnId, checkpoint.sessionId) && phase == TurnState.WAITING_MODEL)
        phase = TurnState.RECEIVING_MODEL
    }

    override fun beginToolBatch(checkpoint: TurnTerminalCheckpoint) {
        check(matches(checkpoint.turnId, checkpoint.sessionId) && phase == TurnState.RECEIVING_MODEL)
        phase = TurnState.RUNNING_TOOL
    }

    override fun commitModelToolStep(
        checkpoint: TurnTerminalCheckpoint,
        toolCallsJson: String,
    ) {
        check(matches(checkpoint.turnId, checkpoint.sessionId) && phase == TurnState.RUNNING_TOOL)
        val calls =
            Json.parseToJsonElement(toolCallsJson).jsonArray.map { value ->
                val row = value.jsonObject
                AssistantToolCall(
                    ToolCallId(row.getValue("id").jsonPrimitive.content),
                    ToolName(row.getValue("name").jsonPrimitive.content),
                    row.getValue("arguments").jsonPrimitive.content,
                )
            }
        history += ModelMessage(ModelRole.ASSISTANT, checkpoint.assistantText, toolCalls = calls)
        log += "assistant-committed"
    }

    override fun persistedPhase(turnId: String) = phase.takeIf { identity?.turnId == turnId }

    override fun persistedAssistantText(turnId: String) = assistantText.takeIf { identity?.turnId == turnId }

    override fun findTurn(turnId: String): TurnLifecycleSnapshot? =
        identity?.takeIf { it.turnId == turnId }?.let {
            TurnLifecycleSnapshot(
                it.turnId,
                it.sessionId,
                requireNotNull(phase),
            )
        }

    override fun sessionBlocker(sessionId: String): SessionTurnBlocker? =
        identity
            ?.takeIf { it.sessionId == sessionId && phase?.isTerminal == false }
            ?.let { SessionTurnBlocker.Live(it.turnId, requireNotNull(phase)) }

    override fun submissionReceipt(
        clientRequestId: String,
        sessionId: String,
        inputFingerprint: String,
    ) = SubmitReceiptDecision.Fresh

    override fun recoveryPredecessor(
        sessionId: String,
        clientRequestId: String,
    ): String? = null

    override fun requestGoalPause(turnId: String): Boolean = false

    private fun matches(
        turnId: String,
        sessionId: String,
    ) = identity?.let { it.turnId == turnId && it.sessionId == sessionId } == true

    override suspend fun resolveReview(command: ReviewResolutionCommand): TurnReviewResolutionResult = unsupported()

    override fun recoverOnStartup(): TurnRecoveryReport = unsupported()

    override fun finishAutomaticRecovery(
        parentId: String,
        reason: String,
        inspectionFailed: Boolean,
    ) = unsupported()

    override fun retryEmptyModelStream(
        checkpoint: TurnTerminalCheckpoint,
        nextModelCallId: String,
    ) = unsupported()

    override fun openNextModelCall(
        checkpoint: TurnTerminalCheckpoint,
        messages: List<TurnMessageDraft>,
        nextModelCallId: String,
    ): ToolResultsCommitReceipt {
        check(matches(checkpoint.turnId, checkpoint.sessionId) && phase == TurnState.RUNNING_TOOL)
        check(checkpoint.modelCallClosed && checkpoint.modelCallId == modelCallId)
        committedDrafts += messages
        messages.forEach { draft ->
            check(draft.role == ModelRole.TOOL)
            val row = Json.parseToJsonElement(draft.content).jsonObject
            history +=
                ModelMessage(
                    ModelRole.TOOL,
                    row.getValue("value").jsonPrimitive.content,
                    toolCallId = ToolCallId(row.getValue("id").jsonPrimitive.content),
                    toolName = ToolName(row.getValue("name").jsonPrimitive.content),
                )
        }
        modelCallId = nextModelCallId
        phase = TurnState.WAITING_MODEL
        log += "results-committed"
        return ToolResultsCommitReceipt.NEXT_MODEL_OPENED
    }

    override fun appendSteeringBeforeRequest(
        checkpoint: TurnTerminalCheckpoint,
        draft: TurnSteeringInput,
    ): Boolean = unsupported()

    override fun completeResponse(
        checkpoint: TurnTerminalCheckpoint,
        draft: TurnSteeringInput?,
        nextModelCallId: String,
    ): ResponseCommitReceipt = unsupported()

    override fun recordDiagnostic(
        modelCallId: String,
        type: String,
        payload: String,
    ) {
        check(this.modelCallId == modelCallId)
        log += "diagnostic:$type"
    }

    override fun recordPromptSnapshot(
        modelCallId: String,
        snapshot: PromptSnapshot?,
        summary: Boolean,
    ) {
        check(this.modelCallId == modelCallId && !summary)
        log += "prompt:$modelCallId"
    }

    override fun recordRequestManifest(
        modelCallId: String,
        manifestJson: String?,
    ) {
        check(this.modelCallId == modelCallId)
        requireNotNull(manifestJson)
        log += "manifest:$modelCallId"
    }

    override fun recordInputRequestStarted(
        checkpoint: TurnTerminalCheckpoint,
        messageIds: Set<String>,
    ) {
        check(matches(checkpoint.turnId, checkpoint.sessionId))
        log += "input:${checkpoint.modelCallId}"
    }

    override fun commitCompaction(
        checkpoint: TurnTerminalCheckpoint,
        plan: ContextCompactionPlan,
        nextModelCallId: String?,
        notice: String?,
        failureReason: String?,
        saveSummary: Boolean,
    ) = unsupported()

    private fun unsupported(): Nothing = error("Unexpected persistence operation in lifecycle scenario")
}

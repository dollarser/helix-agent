package com.helix.app.engine

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.app.review.TurnReviewResolutionResult
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.model.Clock
import com.helix.core.model.GoalState
import com.helix.core.model.ModelRole
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.MessageEntity
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.entity.TurnReviewReceiptEntity
import java.security.MessageDigest

internal object ReviewResolutionConstants {
    const val ABANDON_REASON = "EFFECT_STATE_ACKNOWLEDGED_UNKNOWN"
    const val MODEL_CALL_RUNNING = "RUNNING"
    val parkedTurnStates = setOf(TurnState.NEEDS_REVIEW.name, TurnState.INTERRUPTED.name)
    val parkedCallStates = setOf(ToolCallState.NEEDS_REVIEW, ToolCallState.INTERRUPTED)
    private val terminalCallStates =
        setOf(
            ToolCallState.COMPLETED,
            ToolCallState.FAILED,
            ToolCallState.CANCELLED,
            ToolCallState.DENIED,
        )
    val allowedBatchStates = terminalCallStates + parkedCallStates

    fun isTerminalCall(state: ToolCallState): Boolean = state in terminalCallStates
}

internal class ReviewResolutionReceipt {
    fun fingerprint(command: ReviewResolutionCommand): String {
        val canonical =
            buildString {
                append("review-resolution-v1\u0000")
                append(command.turnId)
                append('\u0000')
                append(command.expectedTurnState?.name.orEmpty())
                command.submissions
                    .sortedBy { it.toolCallId }
                    .forEach {
                        append('\u0000')
                        append(it.toolCallId)
                        append('=')
                        append(it.decision.name)
                    }
            }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun replay(
        receipt: TurnReviewReceiptEntity?,
        turn: TurnEntity,
        command: ReviewResolutionCommand,
        fingerprint: String,
    ): TurnReviewResolutionResult? {
        if (receipt == null) return null
        require(
            receipt.clientActionId == command.clientActionId &&
                receipt.actionFingerprint == fingerprint,
        ) {
            "REVIEW_RESOLUTION_CONFLICT: turn ${turn.id} already resolved by ${receipt.clientActionId}"
        }
        return TurnReviewResolutionResult.Resolved(turn.id)
    }
}

internal data class ReviewParkedBatch(
    val message: MessageEntity,
    val calls: List<ReviewParkedCall>,
)

internal data class ReviewParkedCall(
    val ref: ParkedToolCallRef,
    val entity: ToolCallEntity,
    val state: ToolCallState,
    val result: ToolResultEntity?,
)

internal class ReviewBatchReconciler(
    private val storage: HelixStorage,
    private val clock: Clock,
) {
    fun capture(turn: TurnEntity): ReviewParkedBatch {
        val messages = storage.messages.listBySession(turn.sessionId).filter { it.turnId == turn.id }
        val batchMessage =
            messages
                .filter {
                    it.role == ModelRole.ASSISTANT.name && it.kind == ChatHistoryBuilder.KIND_TOOL_CALLS
                }.maxByOrNull { it.sequence }
                ?: error("PARKED_BATCH_MISSING: no ASSISTANT/TOOL_CALLS row")
        requireNoResultAfter(messages, batchMessage)
        val content =
            requireNotNull(storage.messages.readContent(batchMessage)) {
                "PARKED_BATCH_MALFORMED: missing body"
            }
        val calls = ParkedToolBatchParser.parse(content).map { materialize(turn, it) }
        return ReviewParkedBatch(batchMessage, calls)
    }

    fun revalidate(
        batch: ReviewParkedBatch,
        turn: TurnEntity,
    ) {
        require(storage.messages.resolve(batch.message.id) == batch.message) {
            "PARKED_BATCH_CHANGED: message identity changed"
        }
        val messages = storage.messages.listBySession(turn.sessionId).filter { it.turnId == turn.id }
        val latest =
            messages
                .filter {
                    it.role == ModelRole.ASSISTANT.name && it.kind == ChatHistoryBuilder.KIND_TOOL_CALLS
                }.maxByOrNull { it.sequence }
        require(latest?.id == batch.message.id) { "PARKED_BATCH_CHANGED: newer tool batch exists" }
        requireNoResultAfter(messages, batch.message)
        batch.calls.forEach { captured ->
            require(storage.toolCalls.resolve(captured.entity.id) == captured.entity) {
                "PARKED_BATCH_CHANGED: tool call ${captured.entity.id}"
            }
            require(storage.toolResults.byToolCall(captured.entity.id) == captured.result) {
                "PARKED_BATCH_CHANGED: tool result ${captured.entity.id}"
            }
        }
    }

    fun applySubmissions(
        command: ReviewResolutionCommand,
        batch: ReviewParkedBatch,
    ) {
        val uncertainIds =
            batch.calls
                .filter { it.state in ReviewResolutionConstants.parkedCallStates }
                .mapTo(hashSetOf()) { it.entity.id }
        command.submissions.forEach { submission ->
            require(submission.toolCallId in uncertainIds) {
                "REVIEW_TARGET_MISMATCH: ${submission.toolCallId} is not an uncertain call in the parked batch"
            }
            storage.toolCallReviews.review(
                submission.toolCallId,
                submission.decision,
                submission.reviewedAt ?: clock.now().toEpochMilli(),
            )
        }
    }

    fun decisions(batch: ReviewParkedBatch): Map<ReviewParkedCall, ToolEffectReviewDecision> =
        batch.calls
            .filter { it.state in ReviewResolutionConstants.parkedCallStates }
            .associateWith { call ->
                val review =
                    requireNotNull(storage.toolCallReviews.findByToolCallId(call.entity.id)) {
                        "INCOMPLETE_REVIEWS: missing review for ${call.entity.id}"
                    }
                runCatching { ToolEffectReviewDecision.valueOf(review.decision) }
                    .getOrElse { error("CORRUPT_REVIEW_DECISION: ${review.decision}") }
            }

    private fun materialize(
        turn: TurnEntity,
        ref: ParkedToolCallRef,
    ): ReviewParkedCall {
        val entity = storage.toolCalls.resolve(ref.localId)
        require(entity.turnId == turn.id && entity.callId == ref.localId && entity.name == ref.name) {
            "PARKED_BATCH_IDENTITY_MISMATCH: ${ref.localId}"
        }
        val state = ToolCallState.valueOf(entity.state)
        require(state in ReviewResolutionConstants.allowedBatchStates) {
            "PARKED_BATCH_UNSETTLED: ${entity.id} is $state"
        }
        val result = storage.toolResults.byToolCall(entity.id)
        if (ReviewResolutionConstants.isTerminalCall(state)) {
            requireNotNull(result) { "PARKED_BATCH_RESULT_MISSING: ${entity.id}" }
        }
        return ReviewParkedCall(ref, entity, state, result)
    }

    private fun requireNoResultAfter(
        messages: List<MessageEntity>,
        batchMessage: MessageEntity,
    ) {
        require(
            messages.none {
                it.sequence > batchMessage.sequence &&
                    it.role == ModelRole.TOOL.name &&
                    it.kind == ChatHistoryBuilder.KIND_TOOL_RESULT
            },
        ) { "PARKED_BATCH_ALREADY_HAS_RESULTS: partial result history is not replay-safe" }
    }
}

internal class ReviewGoalRunSettlement(
    private val storage: HelixStorage,
    private val clock: Clock,
) {
    fun closeForContinuation(
        turn: TurnEntity,
        acknowledgedUnknown: Boolean,
    ) {
        val binding = storage.goalTurnBindings.byTurn(turn.id) ?: return
        val run = storage.goalRuns.resolve(binding.runId)
        require(storage.goalUsageReservations.pendingForRun(run.id).isEmpty()) {
            "GOAL_REVIEW_USAGE_PENDING: ${run.id}"
        }
        if (run.endedAt == null) {
            storage.goalRuns.finish(
                run,
                if (acknowledgedUnknown) {
                    "INTERRUPTED(REVIEW_ACKNOWLEDGED_UNKNOWN)"
                } else {
                    "INTERRUPTED(REVIEW_RESOLVED)"
                },
                clock.now().toEpochMilli().coerceAtLeast(run.startedAt),
                run.wakeDurationMillis ?: 0L,
                run.modelCalls,
                run.toolCalls,
                run.tokens,
            )
        }
        val goal = storage.goals.resolve(run.goalId).toRuntimeGoal()
        if (goal.state == GoalState.BLOCKED) {
            val resolved = GoalReducer.reduce(goal, GoalEvent.ReviewResolved)
            check(!resolved.ignored) { "GOAL_REVIEW_RESOLUTION_NOT_APPLICABLE" }
            storage.goals.updateGoal(resolved.state.toStoredGoal())
        }
    }
}

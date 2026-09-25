package com.helix.app.engine

import com.helix.app.review.ToolReviewSubmission
import com.helix.app.review.TurnReviewResolutionResult
import com.helix.core.model.Clock
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity

/** Engine-owned immutable effect-review settlement. Review resolves facts; it never revives an old Turn. */
internal class TurnReviewResolution(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    private val receipts = ReviewResolutionReceipt()
    private val batches = ReviewBatchReconciler(storage, clock)
    private val goals = ReviewGoalRunSettlement(storage, clock)

    suspend fun resolve(command: ReviewResolutionCommand): TurnReviewResolutionResult {
        validateCommand(command)
        val fingerprint = receipts.fingerprint(command)
        val before = storage.turns.resolve(command.turnId)
        val receiptBefore = storage.turnReviewReceipts.find(command.turnId)
        receipts.replay(receiptBefore, before, command, fingerprint)?.let { return it }
        requireReviewable(before, command.expectedTurnState)
        val batch = batches.capture(before)
        var committed: TurnReviewResolutionResult? = null
        storage.withTransaction {
            committed = resolveTransaction(command, fingerprint, batch)
        }
        return requireNotNull(committed)
    }

    private fun resolveTransaction(
        command: ReviewResolutionCommand,
        fingerprint: String,
        batch: ReviewParkedBatch,
    ): TurnReviewResolutionResult {
        val turn = storage.turns.resolve(command.turnId)
        val receipt = storage.turnReviewReceipts.find(command.turnId)
        receipts.replay(receipt, turn, command, fingerprint)?.let { return it }
        requireReviewable(turn, command.expectedTurnState)
        batches.revalidate(batch, turn)
        batches.applySubmissions(command, batch)

        val uncertain = batch.calls.filter { it.state in ReviewResolutionConstants.parkedCallStates }
        require(uncertain.isNotEmpty()) { "REVIEW_NOT_REQUIRED: no uncertain calls" }
        val decisions = batches.decisions(batch)
        val acknowledgedUnknown = decisions.values.any { it == ToolEffectReviewDecision.ACKNOWLEDGED_UNKNOWN }

        storage.turnReviewReceipts.claim(
            turn.id,
            command.clientActionId,
            fingerprint,
        )
        val closed = closeOldAttempt(turn, acknowledgedUnknown)
        goals.closeForContinuation(closed, acknowledgedUnknown)
        appendAudit(closed, command.clientActionId, acknowledgedUnknown)
        return TurnReviewResolutionResult.Resolved(closed.id)
    }

    private fun closeOldAttempt(
        turn: TurnEntity,
        acknowledgedUnknown: Boolean,
    ): TurnEntity =
        if (turn.state == TurnState.NEEDS_REVIEW.name) {
            storage.turns.updateState(
                turn,
                TurnState.INTERRUPTED,
                turn.stepCount,
                clock.now().toEpochMilli().coerceAtLeast(turn.startedAt),
                if (acknowledgedUnknown) ACKNOWLEDGED_UNKNOWN_REASON else REVIEW_RESOLVED_REASON,
            )
        } else {
            turn
        }

    private fun requireReviewable(
        turn: TurnEntity,
        expected: TurnState?,
    ) {
        require(turn.state in ReviewResolutionConstants.parkedTurnStates) {
            "STALE_TURN_STATE: expected reviewable turn, was ${turn.state}"
        }
        expected?.let {
            require(turn.state == it.name) { "STALE_TURN_STATE: expected $it, was ${turn.state}" }
        }
    }

    private fun appendAudit(
        turn: TurnEntity,
        clientActionId: String,
        acknowledgedUnknown: Boolean,
    ) {
        storage.auditEvents.append(
            idGenerator(),
            turn.sessionId,
            "turn.review_resolved",
            "user",
            """{"turnId":"${turn.id}","clientActionId":"$clientActionId","acknowledgedUnknown":$acknowledgedUnknown}""",
            clock.now().toEpochMilli(),
        )
    }

    private fun validateCommand(command: ReviewResolutionCommand) {
        require(command.turnId.isNotBlank()) { "turnId must not be blank" }
        require(command.clientActionId.isNotBlank()) { "clientActionId must not be blank" }
        require(
            command.submissions
                .map { it.toolCallId }
                .distinct()
                .size == command.submissions.size,
        ) {
            "duplicate review submission"
        }
    }

    private companion object {
        const val REVIEW_RESOLVED_REASON = "EFFECT_REVIEW_RESOLVED"
        const val ACKNOWLEDGED_UNKNOWN_REASON = "EFFECT_STATE_ACKNOWLEDGED_UNKNOWN"
    }
}

internal data class ReviewResolutionCommand(
    val turnId: String,
    val clientActionId: String,
    val expectedTurnState: TurnState? = null,
    val submissions: List<ToolReviewSubmission> = emptyList(),
)

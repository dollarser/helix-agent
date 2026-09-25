package com.helix.app.review

import com.helix.app.engine.ReviewResolutionCommand
import com.helix.app.engine.TurnEngine
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ToolCallReviewEntity
import com.helix.core.storage.repository.ToolCallRepository
import com.helix.core.storage.repository.ToolCallReviewRepository

/** Query/presentation facade; Turn lifecycle mutation is owned exclusively by [TurnEngine]. */
class ToolEffectReviewService internal constructor(
    private val toolCallRepository: ToolCallRepository,
    private val toolCallReviewRepository: ToolCallReviewRepository,
    private val turnEngine: TurnEngine? = null,
    private val clock: Clock = SystemClock(),
) {
    internal constructor(
        storage: HelixStorage,
        clock: Clock = SystemClock(),
    ) : this(
        toolCallRepository = storage.toolCalls,
        toolCallReviewRepository = storage.toolCallReviews,
        clock = clock,
    )

    internal constructor(
        storage: HelixStorage,
        clock: Clock,
        turnEngine: TurnEngine,
    ) : this(
        toolCallRepository = storage.toolCalls,
        toolCallReviewRepository = storage.toolCallReviews,
        turnEngine = turnEngine,
        clock = clock,
    )

    fun loadReviewStatus(turnId: String): TurnReviewStatus {
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        val candidateCalls =
            toolCallRepository.listByTurn(turnId).filter {
                ToolCallState.valueOf(it.state) in setOf(ToolCallState.NEEDS_REVIEW, ToolCallState.INTERRUPTED)
            }
        val reviews = toolCallReviewRepository.listByTurn(turnId).associateBy { it.toolCallId }
        return TurnReviewStatus(
            turnId,
            candidateCalls.map { call ->
                val review = reviews[call.id]
                ReviewItemUi(
                    toolCallId = call.id,
                    turnId = call.turnId,
                    callId = call.callId,
                    toolName = call.name,
                    argsJson = call.argsJson,
                    state = ToolCallState.valueOf(call.state),
                    existingDecision = review?.decision?.let(ToolEffectReviewDecision::valueOf),
                    reviewedAt = review?.reviewedAt,
                )
            },
        )
    }

    fun submitReview(
        toolCallId: String,
        decision: ToolEffectReviewDecision,
        reviewedAt: Long = clock.now().toEpochMilli(),
    ): ToolCallReviewEntity = toolCallReviewRepository.review(toolCallId, decision, reviewedAt)

    suspend fun resolveTurnReview(
        turnId: String,
        expectedTurnState: TurnState? = null,
        clientActionId: String,
        submissions: List<ToolReviewSubmission> = emptyList(),
    ): TurnReviewResolutionResult {
        val engine = checkNotNull(turnEngine) { "review lifecycle mutation requires TurnEngine" }
        return engine.resolveReview(
            ReviewResolutionCommand(turnId, clientActionId, expectedTurnState, submissions),
        )
    }
}

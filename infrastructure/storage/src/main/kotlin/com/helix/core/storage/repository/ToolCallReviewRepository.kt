package com.helix.core.storage.repository

import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolEffectReviewDecision
import com.helix.core.model.TurnState
import com.helix.core.storage.dao.ToolCallDao
import com.helix.core.storage.dao.ToolCallReviewDao
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.ToolCallReviewEntity

/**
 * Repository managing persistent human review decisions for uncertain tool effects (ADR-AGENT-001 section 3).
 *
 * Invariants:
 * 1. First review inserts the decision record.
 * 2. Duplicate review with the same decision is idempotent and returns the existing record.
 * 3. Conflicting review with a different decision throws [IllegalStateException] prefixed with
 *    "REVIEW_DECISION_CONFLICT".
 * 4. The target tool call must exist and be in parked/uncertain state ([ToolCallState.NEEDS_REVIEW]
 *    or [ToolCallState.INTERRUPTED]).
 * 5. The parent turn must exist and be in parked/uncertain state (NEEDS_REVIEW or [TurnState.INTERRUPTED]).
 * 6. Neither review insertion nor retrieval mutates the underlying [com.helix.core.storage.entity.ToolCallEntity].
 */
class ToolCallReviewRepository(
    private val reviewDao: ToolCallReviewDao,
    private val toolCallDao: ToolCallDao,
    private val turnDao: TurnDao,
) {
    fun review(
        toolCallId: String,
        decision: ToolEffectReviewDecision,
        reviewedAt: Long,
    ): ToolCallReviewEntity {
        require(toolCallId.isNotBlank()) { "toolCallId must not be blank" }
        require(reviewedAt >= 0) { "reviewedAt must be non-negative: $reviewedAt" }

        val existing = reviewDao.byToolCallId(toolCallId)
        if (existing != null) {
            if (existing.decision == decision.name) {
                return existing
            }
            error("REVIEW_DECISION_CONFLICT: existing decision=${existing.decision}, attempted=${decision.name}")
        }

        val call =
            requireNotNull(toolCallDao.byId(toolCallId)) {
                "tool call not found: $toolCallId"
            }

        val validCallStates = setOf(ToolCallState.NEEDS_REVIEW.name, ToolCallState.INTERRUPTED.name)
        check(call.state in validCallStates) {
            "tool call $toolCallId is in state ${call.state}, must be NEEDS_REVIEW or INTERRUPTED to review"
        }

        val turn =
            requireNotNull(turnDao.byId(call.turnId)) {
                "turn not found for tool call: ${call.turnId}"
            }

        val validTurnStates = setOf(TurnState.NEEDS_REVIEW.name, TurnState.INTERRUPTED.name)
        check(turn.state in validTurnStates) {
            "turn ${call.turnId} is in state ${turn.state}, must be NEEDS_REVIEW or INTERRUPTED to review"
        }

        val entity =
            ToolCallReviewEntity(
                toolCallId = toolCallId,
                decision = decision.name,
                reviewedAt = reviewedAt,
            )
        val inserted = reviewDao.insertIfAbsent(entity)
        return if (inserted != -1L) {
            entity
        } else {
            // Another caller/process won the immutable first-write race after our initial read.
            // Resolve from durable truth rather than leaking a SQLite uniqueness exception.
            val winner =
                checkNotNull(reviewDao.byToolCallId(toolCallId)) {
                    "review insert was ignored but no durable winner exists: $toolCallId"
                }
            if (winner.decision == decision.name) {
                winner
            } else {
                error(
                    "REVIEW_DECISION_CONFLICT: existing decision=" +
                        winner.decision + ", attempted=" + decision.name,
                )
            }
        }
    }

    fun findByToolCallId(toolCallId: String): ToolCallReviewEntity? = reviewDao.byToolCallId(toolCallId)

    fun listByToolCallIds(toolCallIds: List<String>): List<ToolCallReviewEntity> {
        if (toolCallIds.isEmpty()) return emptyList()
        return reviewDao.byToolCallIds(toolCallIds)
    }

    fun listByTurn(turnId: String): List<ToolCallReviewEntity> = reviewDao.listByTurn(turnId)

    fun unresolvedToolCallIds(turnId: String): List<String> {
        val uncertain =
            toolCallDao.listByTurn(turnId).filter {
                it.state in setOf(ToolCallState.NEEDS_REVIEW.name, ToolCallState.INTERRUPTED.name)
            }
        if (uncertain.isEmpty()) return emptyList()
        val reviewed = reviewDao.byToolCallIds(uncertain.map { it.id }).mapTo(mutableSetOf()) { it.toolCallId }
        return uncertain.map { it.id }.filterNot(reviewed::contains)
    }

    fun hasUnresolvedEffects(turnId: String): Boolean = unresolvedToolCallIds(turnId).isNotEmpty()
}

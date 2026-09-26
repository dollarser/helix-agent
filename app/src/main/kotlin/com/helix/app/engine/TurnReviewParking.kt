package com.helix.app.engine

import com.helix.app.agent.BatchCallResolution
import com.helix.app.agent.TurnReviewCheckpoint
import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.model.Clock
import com.helix.core.model.GoalState
import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Engine-owned durable parking of a fully settled UNKNOWN tool batch. */
internal class TurnReviewParking(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    /** Caller owns the outer Room transaction. */
    fun persist(checkpoint: TurnReviewCheckpoint) {
        val turn = storage.turns.resolve(checkpoint.turnId)
        require(turn.sessionId == checkpoint.sessionId) { "turn/session mismatch" }
        val persisted = TurnState.valueOf(turn.state)
        require(persisted in setOf(TurnState.RUNNING_TOOL, TurnState.CANCELLING)) {
            "review park requires live tool state, was $persisted"
        }
        val calls = storage.toolCalls.listByTurn(checkpoint.turnId).associateBy { it.callId }
        checkpoint.batchCalls.keys.forEach { callId ->
            val state = ToolCallState.valueOf(requireNotNull(calls[callId]).state)
            require(
                state !in setOf(ToolCallState.PENDING, ToolCallState.AWAITING_APPROVAL, ToolCallState.RUNNING),
            ) { "tool call is not durably settled: $callId ($state)" }
        }
        checkpoint.reviewCallIds.forEach { callId ->
            require(checkpoint.batchCalls[callId] == BatchCallResolution.UNKNOWN)
            require(calls[callId]?.state == ToolCallState.NEEDS_REVIEW.name) {
                "review call is not durably NEEDS_REVIEW: $callId"
            }
        }
        storage.turns.updateState(turn, TurnState.NEEDS_REVIEW, checkpoint.modelStep, null, null)
        storage.goalTurnBindings.byTurn(checkpoint.turnId)?.let { binding ->
            val run = storage.goalRuns.resolve(binding.runId)
            val goal = storage.goals.resolve(run.goalId).toRuntimeGoal()
            if (goal.state == GoalState.RUNNING) {
                val step = GoalReducer.reduce(goal, GoalEvent.Blocked)
                check(!step.ignored && step.state.state == GoalState.BLOCKED)
                storage.goals.updateGoal(step.state.toStoredGoal())
            } else {
                check(goal.state == GoalState.BLOCKED) {
                    "goal bound to review-parked turn must be RUNNING/BLOCKED, was ${goal.state}"
                }
            }
        }
        storage.auditEvents.append(
            idGenerator(),
            checkpoint.sessionId,
            "turn.needs_review",
            "agent",
            "{\"turn\":\"${checkpoint.turnId}\",\"toolCalls\":[" +
                checkpoint.reviewCallIds.joinToString(",") { "\"$it\"" } + "]}",
            clock.now().toEpochMilli(),
        )
    }
}

package com.helix.app.goal

import com.helix.core.agent.Goal
import com.helix.core.agent.GoalEffect
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage

/** User Continue may finish from existing evidence without inventing a model call or replaying a Tool. */
internal class GoalEvidenceContinue(
    private val storage: HelixStorage,
    private val verifier: GoalCompletionVerifier,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    /** False keeps the ordinary execution path. True means completion was committed or already durable. */
    fun tryComplete(
        goalId: String,
        sessionId: String,
    ): Boolean {
        var completed = false
        storage.withTransaction {
            val previous = storage.goals.resolve(goalId).toRuntimeGoal()
            val owner = storage.goalTurnBindings.sessionForGoal(goalId)
            require(owner == sessionId || previous.runCount == 0) { "Goal belongs to another session" }
            if (previous.state == GoalState.COMPLETED) {
                completed = true
            } else if (previous.criteria.all { it.evidence != null || it.pendingReview != null }) {
                completed = verifyAndComplete(previous, sessionId)
            }
        }
        return completed
    }

    private fun verifyAndComplete(
        previous: Goal,
        sessionId: String,
    ): Boolean {
        require(storage.turns.listActive().none { it.sessionId == sessionId }) { "session has an active Turn" }
        require(storage.goalRuns.listOpenByGoal(previous.id.value).isEmpty()) { "Goal has an open run" }
        require(!storage.goalTurnBindings.hasUnsettledCalls(previous.id.value)) { "Goal has unsettled calls" }
        val continued = GoalReducer.reduce(previous, GoalEvent.Continued(GoalWakeReason.USER_OPEN))
        require(continued.effects.any { it is GoalEffect.StartRun }) { "Goal cannot continue within current budget" }
        val verified = verifier.refresh(continued.state, null)
        val completed = GoalReducer.reduce(verified, GoalEvent.CompleteRequested)
        if (completed.ignored) {
            storage.goals.updateGoal(previous.copy(criteria = verified.criteria).toStoredGoal())
        } else {
            val startedAt = clock.now().toEpochMilli()
            val runId = idGenerator()
            storage.goals.updateGoal(verified.toStoredGoal())
            val run = storage.goalRuns.open(runId, previous.id.value, GoalWakeReason.USER_OPEN.name, startedAt)
            storage.goals.updateGoal(completed.state.toStoredGoal())
            storage.goalRuns.finish(run, "COMPLETED", startedAt, 0, 0, 0, 0)
            audit(previous, runId, "goal.run_started")
            audit(previous, runId, "goal.run_finished")
        }
        return !completed.ignored
    }

    private fun audit(
        goal: Goal,
        runId: String,
        event: String,
    ) {
        val detail =
            kotlinx.serialization.json.buildJsonObject {
                put("runId", kotlinx.serialization.json.JsonPrimitive(runId))
                put("kind", kotlinx.serialization.json.JsonPrimitive("EVIDENCE_VERIFICATION"))
                if (event == "goal.run_finished") put("outcome", kotlinx.serialization.json.JsonPrimitive("COMPLETED"))
            }
        storage.auditEvents.append(
            idGenerator(),
            goal.correlationId.value,
            event,
            "USER",
            detail.toString(),
            clock.now().toEpochMilli(),
        )
    }
}

package com.helix.app.infrastructure.agent

import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.agent.turn.TurnGoalDeliveryInvalidation
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.GoalControlEntity
import com.helix.core.storage.mapping.StoredGoal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One writer of accepted Goal edits after the original Turn durably settles. */
internal class RoomGoalEditSettlement(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val ids: () -> String,
) {
    fun settle(turnId: String): List<TurnGoalDeliveryInvalidation> {
        val discarded = mutableListOf<TurnGoalDeliveryInvalidation>()
        storage.withTransaction {
            val turn = storage.turns.resolve(turnId)
            storage.goalControls.pendingForTurn(turnId).forEach { control ->
                val goal = storage.goals.resolve(control.goalId)
                val eligible =
                    turn.state == "COMPLETED" && turn.pauseRequestedAt == null &&
                        goal.state !in setOf("COMPLETED", "FAILED", "CANCELLED") &&
                        !storage.goalTurnBindings.hasUnsettledCalls(goal.id)
                val applied = eligible && applyEdit(goal, control)
                check(storage.goalControls.settle(control.goalId, control.revision) == 1)
                storage.auditEvents.append(
                    ids(),
                    goal.correlationId,
                    if (applied) "goal.edit_applied" else "goal.edit_discarded",
                    "USER",
                    """{"revision":${storage.goalControls.find(goal.id)?.revision}}""",
                    clock.now().toEpochMilli(),
                )
                if (!applied) discarded += TurnGoalDeliveryInvalidation(control.sessionId, goal.id)
            }
        }
        return discarded.toList()
    }

    private fun applyEdit(
        goal: StoredGoal,
        control: GoalControlEntity,
    ): Boolean {
        val pending = Json.parseToJsonElement(requireNotNull(control.pendingJson)).jsonObject
        if ("budgets" in pending) {
            val values = pending["budgets"]?.jsonObject
            val budgets =
                goal.budgets.copy(
                    maxGoalRounds =
                        values?.get("max_goal_rounds")?.jsonPrimitive?.int ?: goal.budgets.maxGoalRounds,
                )
            val next = GoalReducer.reduce(goal.toRuntimeGoal(), GoalEvent.BudgetsUpdated(budgets))
            if (next.ignored) return false
            storage.goals.updateGoal(next.state.toStoredGoal())
        }
        pending["objective"]?.jsonPrimitive?.content?.let { storage.goals.updateObjective(goal.id, it) }
        if (pending["status"]?.jsonPrimitive?.content == "active" && goal.state == "BLOCKED") {
            com.helix.app.application.agent
                .GoalBlockerResolution(
                    storage,
                    clock,
                    ids,
                ).resolve(goal.id, control.sessionId, contextFits = false)
        }
        return true
    }
}

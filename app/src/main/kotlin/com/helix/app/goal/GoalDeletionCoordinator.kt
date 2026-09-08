package com.helix.app.goal

import com.helix.core.storage.HelixStorage

/** User-only deletion: keep active run state intact and cancel reminders before removing their owner. */
internal class GoalDeletionCoordinator(
    private val storage: HelixStorage,
    private val cancelReminder: (String) -> Unit,
) {
    fun delete(goalId: String) {
        GoalReminderReconciler.serialized {
            storage.withTransaction {
                val goal = storage.goals.resolveEntity(goalId)
                check(goal.state != "RUNNING" && storage.goalRuns.listByGoal(goalId).none { it.endedAt == null }) {
                    "GOAL_ACTIVE_STOP_REQUIRED"
                }
                cancelReminder(goalId)
                storage.auditEvents.deleteByCorrelations(listOf(goal.correlationId, goal.id))
                storage.goals.delete(goalId)
                goal.planId?.takeIf { storage.goals.countByPlan(it) == 0 }?.let(storage.plans::delete)
            }
        }
    }
}

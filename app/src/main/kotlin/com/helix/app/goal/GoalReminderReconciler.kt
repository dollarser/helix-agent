package com.helix.app.goal

import com.helix.core.agent.Checkpoint
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage

/** Rebuilds the optional reminder queue from durable Goal facts; never invokes a run or a provider. */
internal class GoalReminderReconciler(
    private val storage: HelixStorage,
    private val scheduler: GoalReminderScheduler,
    private val clock: Clock,
) {
    fun reconcileAll() {
        storage.goals.list().forEach { reconcile(it.id) }
    }

    fun reconcile(goalId: String) {
        serialized {
            val goal = storage.goals.find(goalId)
            if (goal == null) {
                scheduler.cancelReminder(goalId)
                return@serialized
            }
            val checkpoint = goal.nextCheckpoint
            if (goal.state in setOf("RUNNING", "PAUSED") && checkpoint != null) {
                if (scheduler.wasDelivered(goal.id, checkpoint)) {
                    consumeDelivered(goal.id, checkpoint)
                } else {
                    scheduler.scheduleReminder(
                        goal.id,
                        goal.objective,
                        Checkpoint(checkpoint),
                        clock.now().toEpochMilli(),
                    )
                }
            } else {
                scheduler.cancelReminder(goal.id)
            }
        }
    }

    private fun consumeDelivered(
        goalId: String,
        checkpoint: Long,
    ) {
        storage.withTransaction {
            val current = storage.goals.find(goalId)
            if (current?.nextCheckpoint == checkpoint && current.state in setOf("RUNNING", "PAUSED")) {
                storage.goals.updateGoal(current.copy(nextCheckpoint = null))
                storage.auditEvents.append(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                    current.correlationId,
                    "goal.checkpoint_delivered",
                    "SYSTEM",
                    """{"checkpoint":$checkpoint}""",
                    clock.now().toEpochMilli(),
                )
            }
        }
    }

    companion object {
        private val lock = Any()

        internal fun <T> serialized(action: () -> T): T = synchronized(lock, action)
    }
}

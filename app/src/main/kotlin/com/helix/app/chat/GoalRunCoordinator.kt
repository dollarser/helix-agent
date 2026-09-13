package com.helix.app.chat

import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.goal.toRuntimeGoal
import com.helix.app.goal.toStoredGoal
import com.helix.core.agent.Criterion
import com.helix.core.agent.Goal
import com.helix.core.agent.GoalEffect
import com.helix.core.agent.GoalEvent
import com.helix.core.agent.GoalReducer
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.Clock
import com.helix.core.model.CorrelationId
import com.helix.core.model.GoalBudgets
import com.helix.core.model.GoalId
import com.helix.core.model.PlanId
import com.helix.core.model.Sha256
import com.helix.core.model.TurnBudgets
import com.helix.core.storage.HelixStorage

internal data class GoalTurnStart(
    val goalId: String,
    val wakeReason: GoalWakeReason,
    val turn: TurnStartSpec,
    val turnLimits: TurnBudgets,
)

internal data class StartedGoalTurn(
    val runId: String,
    val coordinator: TurnCoordinator,
    val budgets: TurnBudgets,
    val remainingToolCalls: Int,
)

/** User-action boundary, not a model Tool. It never schedules work or replays an interrupted Turn. */
internal class GoalRunCoordinator(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    fun create(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
        planId: PlanId? = null,
        planHash: Sha256? = null,
    ): String {
        var created: String? = null
        storage.withTransaction {
            created = saveReadyGoal(objective, criteria, budgets, planId, planHash)
        }
        return created ?: error("a new goal must be saved")
    }

    /**
     * Computes and persists a fresh READY goal and returns its id. [create] wraps this in its
     * own transaction; a caller that must commit the goal TOGETHER with other writes — the plan
     * EXECUTING transition (research doc 5.1: restart + double-click execute) — calls this from
     * INSIDE its own storage transaction. Callers MUST already be inside a transaction; this
     * method does not open one.
     *
     * HX2-05: a plan-executing goal binds the approved plan version; the pair is all-or-nothing
     * (StoredGoal enforces the same invariant at the row level).
     */
    fun saveReadyGoal(
        objective: String,
        criteria: List<String>,
        budgets: GoalBudgets,
        planId: PlanId?,
        planHash: Sha256?,
    ): String {
        require((planId == null) == (planHash == null)) { "planId and planHash must be set together" }
        val goal =
            Goal.initial(
                GoalId(idGenerator()),
                objective,
                criteria.mapIndexed { index, description -> Criterion("criterion-$index", description) },
                budgets,
                CorrelationId(idGenerator()),
                planId,
                planHash,
            )
        val ready = GoalReducer.reduce(goal, GoalEvent.Ready(null, null)).state
        storage.goals.save(ready.toStoredGoal())
        audit(ready, "goal.created")
        return ready.id.value
    }

    /** Null means the durable state/budget refuses this wake; no rows or counters change. */
    fun start(request: GoalTurnStart): StartedGoalTurn? {
        var started: StartedGoalTurn? = null
        storage.withTransaction {
            val previous = storage.goals.resolve(request.goalId).toRuntimeGoal()
            val step = GoalReducer.reduce(previous, GoalEvent.Continued(request.wakeReason))
            val effect = step.effects.filterIsInstance<GoalEffect.StartRun>().singleOrNull()
            if (effect != null && !storage.goalTurnBindings.hasUnresolvedCalls(request.goalId)) {
                require(
                    storage.goalRuns.listOpenByGoal(request.goalId).isEmpty(),
                ) { "Goal has an unreconciled open run" }
                val limits = remainingLimits(effect, request.turnLimits)
                val runId = idGenerator()
                storage.goals.updateGoal(step.state.toStoredGoal())
                storage.goalRuns.open(runId, request.goalId, request.wakeReason.name, clock.now().toEpochMilli())
                val turn =
                    TurnCoordinator.start(
                        storage,
                        clock,
                        idGenerator,
                        request.turn.copy(goalRunId = runId),
                    )
                audit(step.state, "goal.run_started")
                started = StartedGoalTurn(runId, turn, limits, effect.remainingToolCalls)
            }
        }
        return started
    }

    /** Updating a parked budget does not itself continue or start any model/tool work. */
    fun updateBudgets(
        goalId: String,
        budgets: GoalBudgets,
    ): Boolean {
        var changed = false
        storage.withTransaction {
            val step =
                GoalReducer.reduce(
                    storage.goals.resolve(goalId).toRuntimeGoal(),
                    GoalEvent.BudgetsUpdated(budgets),
                )
            if (!step.ignored) {
                storage.goals.updateGoal(step.state.toStoredGoal())
                audit(step.state, "goal.budgets_updated")
                changed = true
            }
        }
        return changed
    }

    private fun remainingLimits(
        effect: GoalEffect.StartRun,
        configured: TurnBudgets,
    ): TurnBudgets =
        configured.copy(
            maxModelCalls = minOf(configured.maxModelCalls, effect.remainingModelCalls),
            maxInputTokens = minOf(configured.maxInputTokens, effect.remainingTotalTokens),
            maxOutputTokens = minOf(configured.maxOutputTokens, effect.remainingTotalTokens),
            maxTotalTokens = minOf(configured.maxTotalTokens, effect.remainingTotalTokens),
        )

    fun setCheckpoint(
        goalId: String,
        checkpoint: com.helix.core.agent.Checkpoint?,
    ): Boolean {
        var changed = false
        storage.withTransaction {
            val event = checkpoint?.let { GoalEvent.CheckpointScheduled(it) } ?: GoalEvent.CheckpointCleared
            val step = GoalReducer.reduce(storage.goals.resolve(goalId).toRuntimeGoal(), event)
            if (!step.ignored) {
                storage.goals.updateGoal(step.state.toStoredGoal())
                audit(step.state, "goal.checkpoint_updated")
                changed = true
            }
        }
        return changed
    }

    private fun audit(
        goal: Goal,
        type: String,
    ) {
        storage.auditEvents.append(
            idGenerator(),
            goal.correlationId.value,
            type,
            "USER",
            """{"runCount":${goal.runCount},"state":"${goal.state.name}"}""",
            clock.now().toEpochMilli(),
        )
    }
}

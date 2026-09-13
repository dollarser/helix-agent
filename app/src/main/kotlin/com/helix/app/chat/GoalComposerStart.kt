package com.helix.app.chat

import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.Goal
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.GoalState
import com.helix.core.storage.HelixStorage

internal data class GoalSendStart(
    val required: Boolean,
    val started: StartedGoalTurn?,
)

/** Called under the send gate. Creation and first run commit together; failed admission creates nothing. */
internal class GoalComposerStart(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    fun admit(
        goalId: String?,
        spec: TurnStartSpec,
        control: RunControlConfig,
        retry: Boolean,
    ): GoalSendStart {
        if (goalId != null) {
            val started =
                GoalRunCoordinator(storage, clock, idGenerator)
                    .start(GoalTurnStart(goalId, GoalWakeReason.USER_OPEN, spec, control.budgets))
            return GoalSendStart(true, started)
        }
        val prompt = spec.userText
        val required = control.mode == AgentMode.GOAL && !retry && prompt != ContextCompaction.COMMAND
        val started = if (required && !prompt.isNullOrBlank()) start(prompt, spec, control) else null
        return GoalSendStart(required, started)
    }

    fun start(
        text: String,
        spec: TurnStartSpec,
        control: RunControlConfig,
    ): StartedGoalTurn? {
        require(text.isNotBlank())
        var started: StartedGoalTurn? = null
        storage.withTransaction {
            val coordinator = GoalRunCoordinator(storage, clock, idGenerator)
            val latestGoal =
                storage.turns.listBySession(spec.sessionId).asReversed().firstNotNullOfOrNull { turn ->
                    storage.goalTurnBindings.byTurn(turn.id)?.let { binding ->
                        storage.goals.resolve(storage.goalRuns.resolve(binding.runId).goalId)
                    }
                }
            val existing = latestGoal?.takeUnless { GoalState.valueOf(it.state).isTerminal }
            if (existing != null) {
                started = coordinator.start(GoalTurnStart(existing.id, GoalWakeReason.USER_OPEN, spec, control.budgets))
            } else {
                // Full instructions remain in the first persisted user message; the objective is its compact label.
                val objective = text.trim().take(Goal.MAX_OBJECTIVE_LENGTH)
                val id = coordinator.create(objective, emptyList(), control.goalBudgets)
                started = coordinator.start(GoalTurnStart(id, GoalWakeReason.USER_OPEN, spec, control.budgets))
                check(started != null) { "New Goal could not start" }
            }
        }
        return started
    }
}

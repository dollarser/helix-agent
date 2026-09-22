package com.helix.app.chat

import com.helix.app.goal.toRuntimeGoal
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.GoalContinuationRequest
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalId
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.storage.HelixStorage

/** Called only under ChatService's turn gate. Room owns facts; this owns revocable live activation. */
@Suppress("TooManyFunctions") // One process-local owner for activation, claims and handoff lifetime.
internal class GoalContinuationDriver(
    private val storage: HelixStorage,
) {
    private data class Activation(
        val id: String,
        val goalId: String,
        val providerId: String,
        val control: RunControlConfig,
        val lastTurnId: String,
        val providerSnapshot: String,
        val prepared: Boolean = false,
    )

    private val active = mutableMapOf<String, Activation>()
    private val handoffs = mutableMapOf<String, String>()

    fun prepare(
        sessionId: String,
        goalId: String,
        providerId: String,
        control: RunControlConfig,
        turnId: String,
        providerSnapshot: String,
    ) {
        active[sessionId] = Activation(turnId, goalId, providerId, control, turnId, providerSnapshot, true)
    }

    val hasHandoff: Boolean get() = handoffs.isNotEmpty()

    fun handoffOwner(sessionId: String): String? = handoffs[sessionId]

    fun hasActivation(sessionId: String): Boolean = active.containsKey(sessionId)

    /** User successors do not replace the Goal predecessor or mint a new activation. */
    fun resumeEligible(sessionId: String): SubmitTurnCommand? =
        active[sessionId]?.let { next(sessionId, it.lastTurnId) }

    /** Keep the existing foreground handoff while an admitted user successor is scheduled. */
    fun reserveUserHandoff(
        sessionId: String,
        turnId: String,
    ) {
        handoffs[sessionId] = turnId
    }

    /** Reserve before publishing idle; asynchronous drain rechecks eligibility before submission. */
    fun reserveEligibleHandoff(
        sessionId: String,
        ownerTurnId: String,
    ) {
        val activation = active[sessionId] ?: return
        if (eligible(activation)) {
            handoffs[sessionId] = ownerTurnId
        } else {
            disarm(sessionId)
        }
    }

    fun isArmed(
        sessionId: String,
        goalId: String,
    ): Boolean = active[sessionId]?.goalId == goalId

    fun disarmAll() {
        active.clear()
        handoffs.clear()
    }

    fun disarm(sessionId: String) {
        active.remove(sessionId)
        handoffs.remove(sessionId)
    }

    fun disarmGoal(
        sessionId: String,
        goalId: String,
    ) {
        if (active[sessionId]?.goalId == goalId) disarm(sessionId)
    }

    fun finishHandoff(
        sessionId: String,
        previousTurnId: String,
    ) {
        if (handoffs[sessionId] == previousTurnId) handoffs.remove(sessionId)
    }

    fun disarmTurn(
        sessionId: String,
        turnId: String,
    ) {
        if (active[sessionId]?.lastTurnId == turnId) disarm(sessionId)
    }

    fun started(
        sessionId: String,
        goalId: String,
        providerId: String,
        control: RunControlConfig,
        turnId: String,
        request: GoalContinuationRequest?,
        providerSnapshot: String,
    ) {
        val previous = active[sessionId]
        active[sessionId] =
            Activation(
                request?.activationId ?: turnId,
                goalId,
                providerId,
                if (request != null) previous?.control ?: control else control,
                turnId,
                providerSnapshot,
            )
    }

    fun admits(
        sessionId: String,
        goalId: String?,
        request: GoalContinuationRequest,
        providerSnapshot: String,
    ): Boolean {
        val activation = active[sessionId] ?: return false
        return activation.providerSnapshot == providerSnapshot &&
            activation.id == request.activationId && activation.goalId == goalId &&
            activation.lastTurnId == request.previousTurnId && eligible(activation)
    }

    @Suppress("ReturnCount") // Each rejection revokes or ignores a distinct predecessor claim.
    fun next(
        sessionId: String,
        turnId: String,
    ): SubmitTurnCommand? {
        val activation = active[sessionId] ?: return null
        if (activation.lastTurnId != turnId) return null
        if (!eligible(activation)) {
            disarm(sessionId)
            return null
        }
        handoffs.putIfAbsent(sessionId, turnId)
        return SubmitTurnCommand(
            session = SessionId(sessionId),
            providerId = ProviderId(activation.providerId),
            mode = AgentMode.GOAL,
            text =
                "[Goal continuation] Continue the existing objective using current history and results. " +
                    "Do not repeat settled actions. Read get_goal, then report complete, in_progress or blocked " +
                    "with update_goal.",
            budgets = activation.control.budgets,
            reasoning = activation.control.reasoning,
            goalId = GoalId(activation.goalId),
            clientRequestId = "goal-next-$turnId",
            continuousGoal = true,
            goalContinuation = GoalContinuationRequest(activation.id, turnId),
            goalBudgets = activation.control.goalBudgets,
        )
    }

    @Suppress("ReturnCount") // Prepared human requests and settled automatic predecessors have distinct admission.
    private fun eligible(activation: Activation): Boolean {
        val turn = storage.turns.resolve(activation.lastTurnId)
        val goal = storage.goals.resolve(activation.goalId).toRuntimeGoal()
        if (activation.prepared) {
            return preparedEligible(activation, turn, goal)
        }
        val binding = storage.goalTurnBindings.byTurn(turn.id) ?: return false
        val run = storage.goalRuns.resolve(binding.runId)
        return turn.state == "COMPLETED" && turn.pauseRequestedAt == null && run.endedAt != null &&
            run.outcome == "RUN_FINISHED" && goal.state.name == "PAUSED" && goal.hasRunBudgetHeadroom() &&
            storage.goalRuns
                .listByGoal(activation.goalId)
                .lastOrNull()
                ?.id == run.id &&
            !storage.goalTurnBindings.hasUnsettledCalls(activation.goalId) &&
            storage.toolCalls.listByTurn(turn.id).none { it.state == "DENIED" }
    }

    private fun preparedEligible(
        activation: Activation,
        turn: com.helix.core.storage.entity.TurnEntity,
        goal: com.helix.core.agent.Goal,
    ): Boolean =
        turn.state == "COMPLETED" && turn.pauseRequestedAt == null &&
            goal.state.name in setOf("READY", "PAUSED", "INPUT_REQUIRED") && goal.hasRunBudgetHeadroom() &&
            storage.goalControls.find(activation.goalId)?.pendingJson == null &&
            !storage.goalTurnBindings.hasUnsettledCalls(activation.goalId) &&
            storage.goalRuns.listOpenByGoal(activation.goalId).isEmpty() &&
            storage.toolCalls.listByTurn(turn.id).none {
                it.state in setOf("DENIED", "NEEDS_REVIEW", "INTERRUPTED")
            }
}

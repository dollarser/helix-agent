package com.helix.app.application.agent

import com.helix.app.agent.AutomaticGoalContinuation
import com.helix.core.agent.GoalContinuationRequest
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalId
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId

/** Called only under ChatService's turn gate. Room owns facts; this owns revocable live activation. */
@Suppress("TooManyFunctions") // One process-local owner for activation, claims and handoff lifetime.
internal class GoalContinuationDriver(
    private val factsReader: GoalContinuationFactsReader,
) : com.helix.feature.conversation.application.recovery.GoalContinuationHandoff {
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

    override fun handoffOwner(sessionId: String): String? = handoffs[sessionId]

    fun hasActivation(sessionId: String): Boolean = active.containsKey(sessionId)

    /** User successors do not replace the Goal predecessor or mint a new activation. */
    override fun resumeEligible(sessionId: String): SubmitTurnCommand? =
        active[sessionId]?.let { next(sessionId, it.lastTurnId) }

    /** Keep the existing foreground handoff while an admitted user successor is scheduled. */
    override fun reserveUserHandoff(
        sessionId: String,
        turnId: String,
    ) {
        handoffs[sessionId] = turnId
    }

    /** Reserve before publishing idle; asynchronous drain rechecks eligibility before submission. */
    override fun reserveEligibleHandoff(
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

    override fun disarm(sessionId: String) {
        active.remove(sessionId)
        handoffs.remove(sessionId)
    }

    fun disarmGoal(
        sessionId: String,
        goalId: String,
    ) {
        if (active[sessionId]?.goalId == goalId) disarm(sessionId)
    }

    override fun finishHandoff(
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
        val facts = factsReader.read(activation.goalId, activation.lastTurnId)
        if (!eligible(activation, facts)) {
            disarm(sessionId)
            return null
        }
        handoffs.putIfAbsent(sessionId, turnId)
        return SubmitTurnCommand(
            session = SessionId(sessionId),
            providerId = ProviderId(activation.providerId),
            mode = AgentMode.GOAL,
            text =
                "[Goal continuation ${facts.roundsUsed + 1}/" +
                    "${facts.goal.budgets.maxGoalRounds}] " +
                    "Continue the existing objective using current history and results. " +
                    "Do not repeat settled actions. Read goal.get, then report complete, in_progress or blocked " +
                    "with goal.update.",
            budgets = activation.control.budgets,
            reasoning = activation.control.reasoning,
            goalId = GoalId(activation.goalId),
            clientRequestId = "goal-next-$turnId",
            continuousGoal = true,
            goalContinuation = GoalContinuationRequest(activation.id, turnId),
            goalBudgets = activation.control.goalBudgets,
        )
    }

    private fun eligible(activation: Activation): Boolean =
        eligible(activation, factsReader.read(activation.goalId, activation.lastTurnId))

    @Suppress("ReturnCount") // Round capacity and prepared versus settled predecessors are distinct gates.
    private fun eligible(
        activation: Activation,
        facts: GoalContinuationFacts,
    ): Boolean {
        if (facts.roundsUsed >= facts.goal.budgets.maxGoalRounds) return false
        if (activation.prepared) return preparedEligible(facts)
        return AutomaticGoalContinuation.accepts(
            facts.turnState,
            facts.turnErrorCode,
            facts.pauseRequestedAt,
        ) && facts.predecessorRunFinished &&
            facts.predecessorRunOutcome == "RUN_FINISHED" && facts.goal.state.name == "PAUSED" &&
            facts.goal.hasRunBudgetHeadroom() && facts.predecessorIsLatestRun &&
            !facts.hasUnsettledCalls && !facts.hasDeniedCalls
    }

    private fun preparedEligible(facts: GoalContinuationFacts): Boolean =
        facts.turnState == "COMPLETED" && facts.pauseRequestedAt == null &&
            facts.goal.state.name in setOf("READY", "PAUSED", "INPUT_REQUIRED") && facts.goal.hasRunBudgetHeadroom() &&
            !facts.hasPendingEdit && !facts.hasUnsettledCalls && !facts.hasOpenRuns && !facts.hasDeniedCalls
}

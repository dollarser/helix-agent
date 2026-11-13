package com.helix.app.infrastructure.conversation

import com.helix.app.application.agent.GoalContinuationDriver
import com.helix.app.application.agent.GoalRequestAuthority
import com.helix.app.application.agent.GoalUserRequest
import com.helix.app.goal.GoalLifecycleService
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.goals.ConversationGoalRuntime
import com.helix.feature.conversation.application.recovery.GoalContinuationHandoff
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult

/** Goal tools and request evidence stay in App; the conversation sees only revocable runtime intent. */
internal class AppConversationGoalRuntime(
    private val storage: HelixStorage,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val turnEngine: TurnEngine,
    private val continuation: GoalContinuationDriver,
    goalUserRequests: MutableMap<String, List<GoalUserRequest>>,
) : ConversationGoalRuntime,
    GoalContinuationHandoff by continuation {
    private val authority = GoalRequestAuthority(goalUserRequests) { storage.messages.find(it)?.turnId }
    private val gate get() = turnEngine.executionGate

    override val hasHandoff: Boolean get() = synchronized(gate) { continuation.hasHandoff }

    override fun hasActivation(sessionId: String): Boolean =
        synchronized(gate) { continuation.hasActivation(sessionId) }

    override fun disarmGoal(
        sessionId: String,
        goalId: String,
    ) = synchronized(gate) { continuation.disarmGoal(sessionId, goalId) }

    override fun stopContinuousGoals() =
        synchronized(gate) {
            continuation.disarmAll()
            authority.clear()
        }

    override fun releaseTurnAuthority(turnId: String) = synchronized(gate) { authority.releaseTurn(turnId) }

    override fun bindInputAuthority(
        sessionId: String,
        turnId: String,
        modelCallId: String,
        messageIds: Set<String>,
    ) = synchronized(gate) {
        val control = turnEngine.liveExecution.byTurn(turnId)?.control ?: return@synchronized
        var current = emptyList<GoalUserRequest>()
        storage.withTransaction {
            check(storage.turns.resolve(turnId).sessionId == sessionId)
            val modelCall = storage.modelCalls.resolve(modelCallId)
            check(modelCall.turnId == turnId) { "Model request belongs to a different Turn" }
            current =
                storage.sessionInputs
                    .appendedForMessages(turnId, messageIds)
                    .filter {
                        it.sessionId == sessionId && it.requestModelCallId == modelCallId && it.delegation == null
                    }.map { input ->
                        GoalUserRequest(
                            sessionId,
                            storage.sessionInputs.readText(input),
                            input.configuration.providerId,
                            control,
                            modelCall.providerSnapshot,
                            input.messageId,
                            input.inputId,
                        )
                    }
        }
        authority.bindModelInputs(sessionId, turnId, messageIds, current)
    }

    /** Register directly with GoalTools; feature UI never executes a privileged tool itself. */
    fun executeGoalTool(call: ExecutableToolCall): ToolExecutorResult =
        synchronized(gate) {
            var authorized: GoalUserRequest? = null
            var staged: ActivationChange? = null
            val result =
                GoalLifecycleService(
                    storage,
                    clock,
                    idGenerator,
                    authorize = { request, quote ->
                        authority
                            .authorizedRequest(request.sessionId, request.turnId, quote)
                            .also { authorized = it }
                            ?.control
                            ?.goalBudgets
                    },
                    staged = { session, turn, goal, activate ->
                        if (activate != null) {
                            // Capture the exact quoted authority before the transaction commits, but do
                            // not mutate live activation until durable creation/edit is confirmed.
                            val request = if (activate) requireNotNull(authorized) else null
                            require(request == null || request.sessionId == session)
                            staged = ActivationChange(session, turn, goal, activate, request)
                        }
                    },
                    armed = { session, goal ->
                        staged?.takeIf { it.sessionId == session && it.goalId == goal }?.activate
                            ?: continuation.isArmed(session, goal)
                    },
                ).execute(call)
            if (result is ToolExecutorResult.Completed) staged?.let(::applyActivation)
            result
        }

    private fun applyActivation(change: ActivationChange) {
        if (!change.activate) {
            continuation.disarmGoal(change.sessionId, change.goalId)
        } else {
            val request = checkNotNull(change.request)
            continuation.prepare(
                change.sessionId,
                change.goalId,
                request.providerId,
                request.control,
                change.turnId,
                request.providerSnapshot,
            )
        }
    }

    private data class ActivationChange(
        val sessionId: String,
        val turnId: String,
        val goalId: String,
        val activate: Boolean,
        val request: GoalUserRequest?,
    )
}

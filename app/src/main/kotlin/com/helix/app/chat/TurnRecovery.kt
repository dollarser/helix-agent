package com.helix.app.chat

import com.helix.core.agent.BudgetStopReasons
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import com.helix.feature.conversation.application.recovery.TurnRecoveryActions
import com.helix.feature.conversation.presentation.GoalSummaryUi
import com.helix.feature.conversation.presentation.RecoveryFacts
import com.helix.feature.conversation.presentation.RecoverySummary
import com.helix.feature.conversation.presentation.ToolCallFact
import com.helix.feature.conversation.presentation.TurnRecoverySource
import com.helix.feature.conversation.presentation.recoverySummary

/**
 * Reads the last [limit] settled turns of the session — plus [includeTurnId] when it is a
 * settled turn outside that window (the retry target, so its panel always renders) — and
 * gathers each turn's recovery facts in one Room snapshot. Only facts the UI may show are
 * read: turn state and terminal code, persisted tool call states, the user-pause flag, the
 * bound Goal's own continue admission (one session query shared by every bound turn),
 * budget-continuation admission, and artifact ids. Nothing here mutates state —
 * execution-side admission lives in [TurnRecoveryActions].
 */
internal fun loadTurnRecoverySources(
    storage: HelixStorage,
    sessionId: String,
    limit: Int = DEFAULT_PANEL_WINDOW,
    includeTurnId: String? = null,
): List<TurnRecoverySource> {
    var sources = emptyList<TurnRecoverySource>()
    storage.withTransaction {
        sources = readTurnRecoverySources(storage, sessionId, limit, includeTurnId)
    }
    return sources
}

/** The conversation shows recovery panels for at most this many of the newest settled turns. */
private const val DEFAULT_PANEL_WINDOW = 5

private fun readTurnRecoverySources(
    storage: HelixStorage,
    sessionId: String,
    limit: Int,
    includeTurnId: String?,
): List<TurnRecoverySource> {
    val states = setOf(TurnState.FAILED, TurnState.INTERRUPTED, TurnState.CANCELLED)
    val settled = storage.turns.recentByStates(sessionId, states, limit)
    val extra =
        includeTurnId?.let(storage.turns::find)?.takeIf {
            it.sessionId == sessionId && it.state in states.map(TurnState::name)
        }
    val window = (settled + listOfNotNull(extra)).distinctBy { it.id }
    // Preserve startedAt/rowid ordering, including ties, without materializing session history.
    // Later CANCELLED / INTERRUPTED turns do not supersede an earlier failure.
    // One Goal query per refresh, shared by every bound turn in the window.
    val goals =
        if (window.any { storage.goalTurnBindings.byTurn(it.id) != null }) {
            GoalSummaryQuery(storage).forSession(sessionId).associateBy { it.id }
        } else {
            emptyMap()
        }
    return window.map { turn ->
        recoverySourceFor(
            storage,
            turn,
            goals,
            storage.turns.hasLaterCompleted(turn.id),
        )
    }
}

private fun recoverySourceFor(
    storage: HelixStorage,
    turn: TurnEntity,
    goals: Map<String, GoalSummaryUi>,
    supersededByCompleted: Boolean,
): TurnRecoverySource {
    val binding = storage.goalTurnBindings.byTurn(turn.id)
    val goalId = binding?.let { storage.goalRuns.resolve(it.runId).goalId }
    val goal = goalId?.let { goals[it] }
    // Pre-filter before the O(session) eligibility scan: only budget-code FAILED turns pay it.
    val budgetEligible =
        turn.state == TurnState.FAILED.name &&
            turn.errorCode in BudgetStopReasons.continuable &&
            BudgetContinuation.eligible(storage, turn)
    return TurnRecoverySource(
        turnId = turn.id,
        goalId = goalId,
        goalObjective = goal?.objective,
        facts =
            RecoveryFacts(
                turnState = turn.state,
                turnErrorCode = turn.errorCode,
                toolCalls =
                    storage.toolCalls
                        .listByTurn(turn.id)
                        .map { call ->
                            ToolCallFact(
                                callId = call.callId,
                                toolName = call.name,
                                state = call.state,
                                reviewDecision = storage.toolCallReviews.findByToolCallId(call.id)?.decision,
                            )
                        },
                userPaused = turn.pauseRequestedAt != null,
                goalBound = binding != null,
                goalState = goal?.status?.state,
                goalContinuable = goal?.canContinue == true,
                budgetContinuationEligible = budgetEligible,
                artifactIds = storage.artifacts.listByTurn(turn.id).map { it.id },
            ),
        supersededByCompleted = supersededByCompleted,
        stopFacts =
            if (turn.state == TurnState.CANCELLED.name || turn.pauseRequestedAt != null) {
                loadStopFacts(storage, turn.id)
            } else {
                null
            },
    )
}

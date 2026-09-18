package com.helix.app.chat

import com.helix.app.runcontrol.BudgetStopReasons
import com.helix.app.ui.RecoveryFacts
import com.helix.app.ui.RecoverySummary
import com.helix.app.ui.ToolCallFact
import com.helix.app.ui.recoverySummary
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/**
 * HXA-204 slice 2: one settled turn's recovery panel as rendered in the chat conversation.
 * [summary] is slice 1's read-only projection of persisted facts; [retryAllowed] marks the
 * single turn that owns the new-call retry admission so the retry identity (testTag
 * `chat-retry`) stays unique across panels; [goalId]/[goalObjective] carry the bound Goal's
 * own continue identity (CONTINUE_GOAL is only offered when the Goal's own admission holds).
 */
data class TurnRecoveryPanelUi(
    val turnId: String,
    val turnState: String,
    val summary: RecoverySummary,
    val retryAllowed: Boolean,
    val goalId: String?,
    val goalObjective: String?,
)

/** One turn's recovery facts plus its bound Goal identity, as gathered by [loadTurnRecoverySources]. */
internal data class TurnRecoverySource(
    val turnId: String,
    val goalId: String?,
    val goalObjective: String?,
    val facts: RecoveryFacts,
)

/**
 * Pure: maps [sources] to the panels the UI renders, keyed by turn id. A turn gets a panel
 * only when its projected summary is blocked; the retry operation belongs to the single
 * admitted [retryTargetTurnId], never duplicated across panels.
 */
internal fun recoveryPanelsFor(
    sources: List<TurnRecoverySource>,
    retryTargetTurnId: String?,
): Map<String, TurnRecoveryPanelUi> =
    sources
        .mapNotNull { source ->
            val summary = recoverySummary(source.facts)
            if (!summary.blocked) return@mapNotNull null
            TurnRecoveryPanelUi(
                turnId = source.turnId,
                turnState = source.facts.turnState,
                summary = summary,
                retryAllowed = retryTargetTurnId != null && retryTargetTurnId == source.turnId,
                goalId = source.goalId,
                goalObjective = source.goalObjective,
            )
        }.associateBy { it.turnId }

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
    val settled =
        storage.turns
            .listBySession(sessionId)
            .filter { turn ->
                turn.state in
                    setOf(
                        TurnState.FAILED.name,
                        TurnState.INTERRUPTED.name,
                        TurnState.CANCELLED.name,
                    )
            }
    val extra = includeTurnId?.let { id -> settled.firstOrNull { it.id == id } }
    val window = (settled.takeLast(limit) + listOfNotNull(extra)).distinctBy { it.id }
    // One Goal query per refresh, shared by every bound turn in the window.
    val goals =
        if (window.any { storage.goalTurnBindings.byTurn(it.id) != null }) {
            GoalSummaryQuery(storage).forSession(sessionId).associateBy { it.id }
        } else {
            emptyMap()
        }
    return window.map { turn ->
        val binding = storage.goalTurnBindings.byTurn(turn.id)
        val goalId = binding?.let { storage.goalRuns.resolve(it.runId).goalId }
        val goal = goalId?.let { goals[it] }
        // Pre-filter before the O(session) eligibility scan: only budget-code FAILED turns pay it.
        val budgetEligible =
            turn.state == TurnState.FAILED.name &&
                turn.errorCode in BudgetStopReasons.turn &&
                BudgetContinuation.eligible(storage, turn)
        TurnRecoverySource(
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
                            .map { call -> ToolCallFact(call.callId, call.name, call.state) },
                    userPaused = turn.pauseRequestedAt != null,
                    goalBound = binding != null,
                    goalState = goal?.status?.state,
                    goalContinuable = goal?.canContinue == true,
                    budgetContinuationEligible = budgetEligible,
                    artifactIds = storage.artifacts.listByTurn(turn.id).map { it.id },
                ),
        )
    }
}

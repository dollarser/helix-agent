package com.helix.app.chat

import com.helix.core.model.ToolCallState
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Durable Turn identities, independent of whichever conversation is on screen. */
data class BackgroundTaskUi(
    val id: String,
    val sessionId: String,
    val title: String,
    val state: TurnState,
    val goalId: String?,
    val collected: Boolean,
    val pauseRequested: Boolean,
    val awaitingApproval: Boolean = false,
    val outcome: String? = null,
) {
    val running: Boolean
        get() = !state.isTerminal && state !in setOf(TurnState.NEEDS_REVIEW, TurnState.INTERRUPTED)
    val canCollect: Boolean
        get() = (state.isTerminal || state == TurnState.INTERRUPTED) && !collected
}

internal class BackgroundTaskQuery(
    private val storage: HelixStorage,
) {
    fun read(): List<BackgroundTaskUi> {
        var snapshot = emptyList<BackgroundTaskUi>()
        storage.withTransaction { snapshot = readSnapshot() }
        return snapshot
    }

    private fun readSnapshot(): List<BackgroundTaskUi> =
        (storage.turns.pendingTasks() + storage.turns.recent())
            .distinctBy { it.id }
            .sortedByDescending { it.startedAt }
            .map { turn ->
                val binding = storage.goalTurnBindings.byTurn(turn.id)
                val awaitingApproval =
                    storage.toolCalls.listByTurn(turn.id).any { it.state == ToolCallState.AWAITING_APPROVAL.name }
                BackgroundTaskUi(
                    turn.id,
                    turn.sessionId,
                    storage.sessions.resolve(turn.sessionId).title,
                    TurnState.valueOf(turn.state),
                    binding?.let { storage.goalRuns.resolve(it.runId).goalId },
                    turn.resultCollectedAt != null,
                    turn.pauseRequestedAt != null,
                    awaitingApproval,
                    binding?.let { storage.goalRuns.resolve(it.runId).outcome },
                )
            }
}

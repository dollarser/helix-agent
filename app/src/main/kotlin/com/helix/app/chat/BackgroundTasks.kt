package com.helix.app.chat

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
    fun read(sessionIds: Set<String>? = null): List<BackgroundTaskUi> {
        var snapshot = emptyList<BackgroundTaskUi>()
        storage.withTransaction { snapshot = readSnapshot(sessionIds) }
        return snapshot
    }

    private fun readSnapshot(sessionIds: Set<String>?): List<BackgroundTaskUi> {
        val turns =
            (
                sessionIds?.flatMap(storage.turns::listBySession)
                    ?: (storage.turns.pendingTasks() + storage.turns.recent())
            ).distinctBy { it.id }
                .sortedByDescending { it.startedAt }
        val metadata = storage.turns.backgroundMetadata(turns.map { it.id }).associateBy { it.turnId }
        return turns.map { turn ->
            val facts = metadata.getValue(turn.id)
            check(facts.runId == null || facts.goalId != null) { "Missing task Goal run" }
            BackgroundTaskUi(
                turn.id,
                turn.sessionId,
                facts.title,
                TurnState.valueOf(turn.state),
                facts.goalId,
                turn.resultCollectedAt != null,
                turn.pauseRequestedAt != null,
                facts.awaitingApproval,
                facts.goalOutcome,
            )
        }
    }
}

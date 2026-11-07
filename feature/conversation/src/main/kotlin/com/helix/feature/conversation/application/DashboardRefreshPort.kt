package com.helix.feature.conversation.application

import com.helix.core.model.TurnState
import kotlinx.coroutines.flow.StateFlow

/** Conversation state-change notifications; cross-feature task/job/artifact projections stay in App. */
interface DashboardRefreshPort {
    val foregroundTransport: StateFlow<TurnState?>

    fun refreshBackground()

    suspend fun refreshTasks()

    /** Suppresses duplicate persisted-state refreshes without exposing task dashboard DTOs. */
    fun onTurnChanged(
        turnId: String,
        state: TurnState,
    )
}

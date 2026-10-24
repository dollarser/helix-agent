package com.helix.app.infrastructure.agent

import com.helix.core.agent.turn.SessionTurnBlocker
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage

/** Room-authoritative admission blocker. Process-local Job maps are never an admission authority. */
internal class DurableSessionGate(
    private val storage: HelixStorage,
) {
    fun blocker(sessionId: String): SessionTurnBlocker? {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val active =
            storage.turns
                .listBySession(sessionId)
                .filter {
                    val state = TurnState.valueOf(it.state)
                    !state.isTerminal && state != TurnState.NEEDS_REVIEW
                }
        return when {
            active.isEmpty() -> {
                null
            }

            active.size > 1 -> {
                SessionTurnBlocker.Conflict(active.map { it.id })
            }

            else -> {
                val turn = active.single()
                val state = TurnState.valueOf(turn.state)
                SessionTurnBlocker.Live(turn.id, state)
            }
        }
    }
}

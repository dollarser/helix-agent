package com.helix.app.engine

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

internal sealed interface SessionTurnBlocker {
    val code: String

    data class Live(
        val turnId: String,
        val state: TurnState,
    ) : SessionTurnBlocker {
        override val code: String = "SESSION_TURN_BUSY"
    }

    data class Conflict(
        val turnIds: List<String>,
    ) : SessionTurnBlocker {
        override val code: String = "SESSION_TURN_CONFLICT"
    }
}

package com.helix.app.infrastructure.agent

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionInputState
import com.helix.feature.conversation.application.recovery.QueuedSessionWork
import com.helix.feature.conversation.application.recovery.SessionWorkSnapshot
import com.helix.feature.conversation.application.recovery.SessionWorkStore
import com.helix.feature.conversation.application.recovery.TerminalWorkFacts

/** One Room snapshot for queue precedence and stop/archival state. */
internal class RoomSessionWorkStore(
    private val storage: HelixStorage,
) : SessionWorkStore {
    override fun snapshot(sessionId: String): SessionWorkSnapshot =
        read {
            val session = storage.sessions.find(sessionId)
            val turn = storage.turns.listBySession(sessionId).lastOrNull()
            val queue = storage.sessionInputs.headQueue(sessionId)
            SessionWorkSnapshot(
                stopped =
                    session == null || session.archivedAt != null || turn?.state == "CANCELLED" ||
                        turn?.pauseRequestedAt != null || turn?.errorCode == "USER_STOP",
                queue =
                    queue?.let {
                        QueuedSessionWork(
                            it.inputId,
                            it.sessionId,
                            it.revision,
                            it.state == SessionInputState.PENDING,
                            it.blockedReason,
                        )
                    },
                hasPending = storage.sessionInputs.listPending(sessionId).isNotEmpty(),
            )
        }

    override fun terminalFacts(
        sessionId: String,
        turnId: String,
    ): TerminalWorkFacts =
        read {
            val turn = storage.turns.resolve(turnId)
            require(turn.sessionId == sessionId)
            TerminalWorkFacts(
                goalBound = storage.goalTurnBindings.byTurn(turnId) != null,
                userStopped =
                    turn.state == "CANCELLED" || turn.pauseRequestedAt != null || turn.errorCode == "USER_STOP",
                continuationAllowed =
                    turn.state == "COMPLETED" && turn.errorCode == null && turn.pauseRequestedAt == null,
            )
        }

    override fun parkAfterFailure(
        sessionId: String,
        at: Long,
    ) {
        storage.sessionInputs.parkSessionInputs(sessionId, "INPUT_DELIVERY_FAILED", at)
    }

    private fun <T> read(block: () -> T): T {
        var value: T? = null
        storage.withTransaction { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

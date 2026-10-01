package com.helix.app.agent

import com.helix.core.agent.AgentInputBoundary
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.agent.TurnTerminalCheckpoint
import kotlinx.coroutines.yield

/** Input records and the terminal-vs-steering transaction stay in the existing host owner. */
internal class TurnInputBoundaryAdapter(
    private val sessionId: String,
    private val coordinator: TurnCoordinator,
    private val delivery: TurnInputDelivery,
    private val idGenerator: () -> String,
    private val persistTerminal: (TurnTerminalCheckpoint, ModelStreamTerminal) -> ModelStreamTerminal,
) : AgentInputBoundary {
    override suspend fun appendBeforeRequest(
        sessionId: String,
        turnId: String,
    ): Boolean {
        requireOwner(sessionId, turnId)
        var updated = false
        var remaining = 32
        while (remaining-- > 0) {
            val input = delivery.prepareSteering(sessionId, turnId) ?: break
            updated = coordinator.appendSteeringBeforeRequest(input) || updated
        }
        return updated
    }

    override suspend fun finishResponse(
        sessionId: String,
        turnId: String,
    ): ResponseInputBoundary {
        requireOwner(sessionId, turnId)
        var boundary: ResponseInputBoundary
        do {
            val steering = delivery.prepareSteering(sessionId, turnId)
            boundary = coordinator.completeResponseOrContinue(steering, idGenerator(), persistTerminal)
            if (boundary == ResponseInputBoundary.RECHECK) yield()
        } while (boundary == ResponseInputBoundary.RECHECK)
        return boundary
    }

    override suspend fun requestStarting(
        sessionId: String,
        turnId: String,
        modelCallId: String,
        messageIds: Set<String>,
    ) {
        requireOwner(sessionId, turnId)
        require(modelCallId == coordinator.snapshot().modelCallId) { "INPUT_MODEL_CALL_MISMATCH" }
        delivery.requestStarting(sessionId, turnId, modelCallId, messageIds)
    }

    private fun requireOwner(
        session: String,
        turn: String,
    ) {
        require(session == sessionId && turn == coordinator.id) { "INPUT_TURN_OWNER_MISMATCH" }
    }
}

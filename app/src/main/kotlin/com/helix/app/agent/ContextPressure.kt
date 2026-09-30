package com.helix.app.agent

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.transportIdentity

/** Last real input is a conservative floor when local estimates miss provider/image overhead. */
internal object ContextPressure {
    @Suppress("ReturnCount") // Each missing or superseded measurement has no usable floor.
    fun inputFloor(
        storage: HelixStorage,
        sessionId: String,
        currentTurnId: String,
        model: String,
    ): Long {
        val turns = storage.turns.listBySession(sessionId)
        val currentIndex = turns.indexOfFirst { it.id == currentTurnId }
        val calls =
            turns
                .take(if (currentIndex >= 0) currentIndex + 1 else turns.size)
                .flatMap { storage.modelCalls.listByTurn(it.id) }
        val checkpoint = ContextHistory.checkpoint(storage, sessionId)
        val boundary = calls.indexOfFirst { it.id == checkpoint?.sourceCallId }
        val eligible = if (boundary >= 0) calls.drop(boundary + 1) else calls
        val call =
            eligible.lastOrNull {
                it.state == "COMPLETED" && it.usage != null && !ChatContextProjection.isSummary(storage, it.id)
            } ?: return 0
        val providerId = storage.sessions.resolve(sessionId).providerId ?: return 0
        val config = storage.providerConfigs.resolve(providerId)
        return ChatContextProjection.inputFor(call.providerSnapshot, call.usage, config.transportIdentity, model) ?: 0
    }
}

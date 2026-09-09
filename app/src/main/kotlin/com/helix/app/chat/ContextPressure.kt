package com.helix.app.chat

import com.helix.core.storage.HelixStorage

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
        val previousTurn = turns.lastOrNull { it.id != currentTurnId }
        val calls =
            previousTurn?.let { storage.modelCalls.listByTurn(it.id) }.orEmpty() +
                storage.modelCalls.listByTurn(currentTurnId)
        val checkpoint = ContextCompaction.checkpoint(storage, storage.messages.listBySession(sessionId))
        val boundary = calls.indexOfFirst { it.id == checkpoint?.sourceCallId }
        val eligible = if (boundary >= 0) calls.drop(boundary + 1) else calls
        val call = eligible.lastOrNull { it.state == "COMPLETED" && it.usage != null } ?: return 0
        val providerId = storage.sessions.resolve(sessionId).providerId ?: return 0
        val config = storage.providerConfigs.resolve(providerId)
        return ChatContextProjection.inputFor(call.providerSnapshot, call.usage, config.endpoint, model) ?: 0
    }
}

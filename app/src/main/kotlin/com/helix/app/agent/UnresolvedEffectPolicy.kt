package com.helix.app.agent

import com.helix.core.model.ToolOperationClass
import com.helix.core.storage.HelixStorage

/** Session-wide effect uncertainty gate. Review facts, not old Turn liveness, clear the gate. */
internal object UnresolvedEffectPolicy {
    fun unresolvedTurnIds(
        storage: HelixStorage,
        sessionId: String,
    ): List<String> =
        storage.turns.listBySession(sessionId).mapNotNull { turn ->
            turn.id.takeIf { storage.toolCallReviews.hasUnresolvedEffects(turn.id) }
        }

    fun hasUnresolvedEffects(
        storage: HelixStorage,
        sessionId: String,
    ): Boolean = unresolvedTurnIds(storage, sessionId).isNotEmpty()

    fun permits(
        hasUnresolvedEffects: Boolean,
        operationClass: ToolOperationClass,
    ): Boolean = !hasUnresolvedEffects || operationClass == ToolOperationClass.READ_ONLY
}

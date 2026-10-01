package com.helix.app.agent

import com.helix.core.model.ToolOperationClass
import com.helix.core.storage.HelixStorage

/** Historical uncertainty remains inspectable, not a session-wide execution lock. */
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

    /** Automatic inspection was authorized only to inspect; a new user task uses ordinary policy instead. */
    fun permitsInspection(
        inspection: Boolean,
        operationClass: ToolOperationClass,
    ): Boolean = !inspection || operationClass == ToolOperationClass.READ_ONLY
}

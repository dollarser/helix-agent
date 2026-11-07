package com.helix.feature.conversation.application.goals

import com.helix.feature.conversation.application.recovery.GoalContinuationHandoff

/**
 * Revocable Goal activation and original human-request authority, owned by the App runtime.
 * Operations are synchronous under the same TurnEngine admission gate, including terminal cleanup.
 * Persisted Goals and model output cannot manufacture the retained human-request authority.
 */
interface ConversationGoalRuntime : GoalContinuationHandoff {
    val hasHandoff: Boolean

    fun hasActivation(sessionId: String): Boolean

    fun disarmGoal(
        sessionId: String,
        goalId: String,
    )

    /** Revoke every live activation and retained request before stopping their owned Turns. */
    fun stopContinuousGoals()

    /** Retain only human input actually included in this original Turn's model request. */
    fun bindInputAuthority(
        sessionId: String,
        turnId: String,
        modelCallId: String,
        messageIds: Set<String>,
    )

    /** Must finish before terminal owner release, including failed cleanup paths. */
    fun releaseTurnAuthority(turnId: String)
}

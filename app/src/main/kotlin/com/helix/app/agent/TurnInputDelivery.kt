package com.helix.app.agent

/** Checks a queued steering snapshot without running a model or holding the storage transaction. */
internal interface TurnInputDelivery {
    suspend fun prepareSteering(
        sessionId: String,
        turnId: String,
    ): TurnSteeringDraft?

    /** The bounded local request boundary, not a claim of remote receipt. */
    fun requestStarting(
        sessionId: String,
        turnId: String,
        modelCallId: String,
        messageIds: Set<String>,
    )
}

package com.helix.core.agent

/** The single request-construction port; host gathers facts, Core owns request values. */
interface TurnContextAssembler {
    /** The first model request of the turn: persisted history ending with the user message. */
    suspend fun build(
        sessionId: String,
        turnId: String,
        retryTurnId: String?,
        control: RunControlConfig,
    ): TurnContextRequest

    /** The request after a compaction commit. */
    suspend fun rebuild(
        sessionId: String,
        turnId: String,
        retryTurnId: String?,
        control: RunControlConfig,
        previous: TurnContextRequest,
    ): TurnContextRequest

    /** The next tool-loop request: the persisted history ending with the just-settled tool results. */
    suspend fun buildBackfill(
        sessionId: String,
        turnId: String,
        control: RunControlConfig,
    ): TurnContextRequest
}

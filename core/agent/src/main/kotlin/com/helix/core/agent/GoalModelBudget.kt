package com.helix.core.agent

import com.helix.core.model.ModelRequest

/** Goal reservations are persisted by the host; a model cannot create or enlarge its budget. */
interface GoalModelBudget {
    suspend fun prepare(
        turnId: String,
        callId: String,
        request: ModelRequest,
    ): ModelRequest?

    suspend fun finish(
        turnId: String,
        callId: String,
        request: ModelRequest,
        stream: ModelStreamState,
    )

    suspend fun canContinue(turnId: String): Boolean
}

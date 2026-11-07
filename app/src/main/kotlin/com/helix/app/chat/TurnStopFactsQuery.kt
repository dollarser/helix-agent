package com.helix.app.chat

import com.helix.core.model.ToolCallState
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.presentation.StopActionFact
import com.helix.feature.conversation.presentation.TurnStopFacts
import com.helix.feature.conversation.presentation.auditProvesNotStarted
import com.helix.feature.conversation.presentation.stopEffect

internal fun loadStopFacts(
    storage: HelixStorage,
    turnId: String,
): TurnStopFacts =
    TurnStopFacts(
        storage.toolCalls.listByTurn(turnId).map { call ->
            val result = storage.toolResults.byToolCall(call.callId)
            val dispatch = storage.auditEvents.recentByCorrelation(call.callId, "tool_dispatch", 1).singleOrNull()
            val neverStarted = auditProvesNotStarted(dispatch?.redactedPayload)
            StopActionFact(
                call.callId,
                call.name,
                stopEffect(
                    ToolCallState.entries.firstOrNull { it.name == call.state },
                    result?.status,
                    result?.verified == true,
                    neverStarted,
                ),
            )
        },
        storage.artifacts.listByTurn(turnId).count { it.purpose == "OUTPUT" },
    )

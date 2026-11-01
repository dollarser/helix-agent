package com.helix.app.chat

import com.helix.core.model.ToolCallState
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/** Tool verification is a recorded tool effect, never a claim of business completion. */
enum class StopEffect { VERIFIED, RECORDED, UNKNOWN, NOT_STARTED }

data class StopActionFact(
    val callId: String,
    val name: String,
    val effect: StopEffect,
)

data class TurnStopFacts(
    val actions: List<StopActionFact>,
    val artifactCount: Int,
) {
    fun continuationContext(): String =
        buildString {
            append("Host stop facts (tool effects only; not business success or rollback):\n")
            actions.forEach { append("${it.callId} ${it.name}: ${it.effect.name}\n") }
            append("Registered output artifacts: $artifactCount. ")
            append("Unknown effects must be observed, never replayed blindly.")
        }
}

internal fun stopEffect(
    state: ToolCallState?,
    resultStatus: String?,
    verified: Boolean,
    neverStarted: Boolean,
): StopEffect =
    when {
        state == ToolCallState.COMPLETED && resultStatus == "SUCCEEDED" && verified -> {
            StopEffect.VERIFIED
        }

        state == ToolCallState.COMPLETED && resultStatus == "SUCCEEDED" -> {
            StopEffect.RECORDED
        }

        neverStarted || state in
            setOf(
                ToolCallState.PENDING,
                ToolCallState.AWAITING_APPROVAL,
                ToolCallState.DENIED,
            )
        -> {
            StopEffect.NOT_STARTED
        }

        else -> {
            StopEffect.UNKNOWN
        }
    }

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

/** Missing or malformed audit cannot prove non-execution. */
internal fun auditProvesNotStarted(payload: String?): Boolean =
    try {
        val fields = payload?.let { Json.parseToJsonElement(it).jsonObject }
        fields?.containsKey("executionStartedAt") == true && fields["executionStartedAt"] == JsonNull
    } catch (_: IllegalArgumentException) {
        false
    }

package com.helix.feature.conversation.presentation

import com.helix.core.model.ToolCallState
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

fun stopEffect(
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

/** Missing or malformed audit cannot prove non-execution. */
fun auditProvesNotStarted(payload: String?): Boolean =
    try {
        val fields = payload?.let { Json.parseToJsonElement(it).jsonObject }
        fields?.containsKey("executionStartedAt") == true && fields["executionStartedAt"] == JsonNull
    } catch (_: IllegalArgumentException) {
        false
    }

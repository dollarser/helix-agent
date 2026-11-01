package com.helix.app.chat

import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

internal enum class HumanHandoffState { REQUESTED, SUBMITTED, FINISHED, CANCELLED }

internal data class HumanHandoffUi(
    val turnId: String,
    val state: HumanHandoffState,
    val canReturn: Boolean,
)

/** Audit metadata records the explicit human decision; Room Turn facts still own execution. */
internal class HumanHandoff(
    private val storage: HelixStorage,
    private val now: () -> Long,
) {
    fun state(turnId: String): HumanHandoffState? =
        storage.auditEvents
            .recentByCorrelation(turnId, TYPE, 1)
            .singleOrNull()
            ?.let { parseHumanHandoffState(it.redactedPayload) }

    fun record(
        turnId: String,
        state: HumanHandoffState,
    ) {
        storage.auditEvents.append(
            UUID.randomUUID().toString(),
            turnId,
            TYPE,
            "USER",
            buildJsonObject { put("state", state.name) }.toString(),
            now(),
        )
    }

    fun canReturn(
        sessionId: String,
        turnId: String,
    ): Boolean {
        val turn = storage.turns.find(turnId) ?: return false
        return turn.sessionId == sessionId && state(turnId) == HumanHandoffState.REQUESTED &&
            (TurnState.valueOf(turn.state).isTerminal || turn.state == TurnState.NEEDS_REVIEW.name) &&
            storage.turns.latestVisible(sessionId)?.id == turnId
    }

    fun project(turnId: String): HumanHandoffUi? =
        storage.turns.find(turnId)?.let { turn ->
            state(turnId)?.let { decision ->
                HumanHandoffUi(turnId, decision, canReturn(turn.sessionId, turnId))
            }
        }

    companion object {
        private const val TYPE = "human_handoff"
    }
}

/** Unknown metadata never admits a hand-back. Execution admission still belongs to the runtime. */
internal fun parseHumanHandoffState(payload: String): HumanHandoffState? =
    try {
        val name =
            Json
                .parseToJsonElement(payload)
                .jsonObject["state"]
                ?.jsonPrimitive
                ?.content
        HumanHandoffState.entries.firstOrNull { it.name == name }
    } catch (_: IllegalArgumentException) {
        null
    }

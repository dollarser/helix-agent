package com.helix.app.proot

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ToolCallEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Local binding and receipt projection only; never previews archives or cold-binds the Runtime. */
internal object DetachedJobDashboard {
    fun read(storage: HelixStorage): List<BackgroundJobUi> {
        var rows = emptyList<BackgroundJobUi>()
        storage.withTransaction {
            rows = storage.toolCalls.detachedJobCandidates().map { project(storage, it) }
        }
        return rows
    }

    private fun project(
        storage: HelixStorage,
        call: ToolCallEntity,
    ): BackgroundJobUi {
        val turn = storage.turns.resolve(call.turnId)
        val title = storage.sessions.resolve(turn.sessionId).title
        val terminal =
            runCatching {
                val binding = ProotJobBindingStore(storage).resolveDetached(turn.sessionId, call.callId)
                DetachedJobObservationStore(storage).read(binding)
            }
        if (terminal.isFailure) {
            return BackgroundJobUi(call.callId, turn.id, turn.sessionId, title, CommandDetailState.UNKNOWN, true)
        }
        val result = storage.toolResults.byToolCall(call.callId)
        val accepted =
            runCatching {
                // Terminal receipts stand on their own; an evicted start body must not hide them.
                if (terminal.getOrNull() != null) return@runCatching false
                val content = result?.let(storage.toolResults::readContent)
                val obj = Json.parseToJsonElement(content.orEmpty()) as? JsonObject
                (obj?.get("accepted") as? JsonPrimitive)?.content == "true"
            }.getOrDefault(false)
        val facts = CommandResultFacts(call.state, turn.state, turn.sessionId, result?.status, result?.summary, null)
        val state =
            DetachedCommandProjection.state(facts, accepted, terminal.getOrNull()) {
                if (call.state in setOf("FAILED", "DENIED")) CommandDetailState.FAILED else CommandDetailState.UNKNOWN
            }
        val pending =
            terminal.getOrNull()?.settled != true &&
                (terminal.getOrNull() != null || call.state !in setOf("FAILED", "DENIED"))
        return BackgroundJobUi(call.callId, turn.id, turn.sessionId, title, state, pending)
    }
}

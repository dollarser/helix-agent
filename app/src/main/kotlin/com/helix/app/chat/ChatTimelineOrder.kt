package com.helix.app.chat

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.app.agent.ContextHistory
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Display order comes from the existing persisted protocol, never wall-clock result arrival. */
internal object ChatTimelineOrder {
    fun positions(
        storage: HelixStorage,
        sessionId: String,
    ): Map<String, ToolTimelinePosition> = positions(storage, storage.messages.recentBySession(sessionId, 256))

    fun positions(
        storage: HelixStorage,
        history: List<com.helix.core.storage.entity.MessageEntity>,
    ): Map<String, ToolTimelinePosition> =
        history
            .filter { it.kind == ChatHistoryBuilder.KIND_TOOL_CALLS && it.role == "ASSISTANT" }
            .flatMap { row ->
                val content =
                    try {
                        ContextHistory.read(storage, row)
                    } catch (_: IllegalArgumentException) {
                        // Unreadable display history has no position; execution validation stays strict.
                        null
                    }
                val calls =
                    ChatHistoryBuilder
                        .toModelMessages(
                            listOf(
                                ChatHistoryBuilder.PersistedRow(
                                    row.turnId,
                                    row.role,
                                    row.kind,
                                    content,
                                    row.id,
                                ),
                            ),
                        ).flatMap { it.toolCalls }
                if (calls.isEmpty()) return@flatMap emptyList()
                val refs = Json.parseToJsonElement(requireNotNull(content)) as JsonArray
                calls.mapIndexed { index, call ->
                    val localId =
                        ((refs[index] as? JsonObject)?.get("localId") as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                            ?.takeIf { it.isNotBlank() }
                    (localId ?: call.id.value) to ToolTimelinePosition(row.sequence, index)
                }
            }.toMap()
}

package com.helix.app.todo

import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One row of the conversation's Progress section (HX2-07, research doc section 16).
 * Public: it is projected into the public [com.helix.app.chat.ChatScreenState.taskLedger].
 */
data class LedgerItemUi(
    val id: String,
    val title: String,
    val state: String,
)

/**
 * Projects the model's working-memory ledger (HX2-07) from the persisted `todo.write`
 * tool calls: the latest SUCCESSFUL + VERIFIED call in the newest turn that has one.
 * Deliberately NOT the `goal.report` "last tool call" rule — progress outlives the steps
 * the model takes after writing it; only a later successful `todo.write` replaces it.
 */
internal object TaskLedgerProjection {
    fun forSession(
        storage: HelixStorage,
        sessionId: String,
    ): List<LedgerItemUi> {
        var found: List<LedgerItemUi>? = null
        storage.withTransaction { found = query(storage, sessionId) }
        return found ?: emptyList()
    }

    private fun query(
        storage: HelixStorage,
        sessionId: String,
    ): List<LedgerItemUi> {
        // listBySession is startedAt ASC (oldest first) — scan newest first so the most
        // recent ledger wins; an older turn's progress is only a fallback.
        for (turn in storage.turns.listBySession(sessionId).asReversed()) {
            val args = latestTodoWriteArgs(storage, turn.id) ?: continue
            val items = itemsFromArgs(args)
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    private fun latestTodoWriteArgs(
        storage: HelixStorage,
        turnId: String,
    ): JsonObject? =
        storage.toolCalls
            .listByTurn(turnId)
            .lastOrNull { it.name == TodoWriteTool.NAME && it.state == "COMPLETED" }
            ?.let { call ->
                val result = storage.toolResults.byToolCall(call.id)
                if (result != null && result.status == "SUCCEEDED" && result.verified) {
                    runCatching { Json.parseToJsonElement(call.argsJson).jsonObject }.getOrNull()
                } else {
                    null
                }
            }

    /**
     * Parses a `todo.write` args object into UI rows. Unknown/blank fields are skipped and
     * anything malformed yields an EMPTY list (fail-closed: a corrupt row never renders a
     * partial or fake ledger).
     */
    fun itemsFromArgs(args: JsonObject): List<LedgerItemUi> {
        val items = args["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { rowFor(it) }
    }

    /** One row; a blank id/title or an unknown state skips the row (null). */
    private fun rowFor(element: JsonElement): LedgerItemUi? =
        (element as? JsonObject)?.let { item ->
            (item["state"] as? JsonPrimitive)?.content?.takeIf { state -> state in STATES }?.let { state ->
                LedgerItemUi(
                    id = (item["id"] as? JsonPrimitive)?.content.orEmpty(),
                    title = (item["title"] as? JsonPrimitive)?.content.orEmpty(),
                    state = state,
                ).takeIf { row -> row.id.isNotBlank() && row.title.isNotBlank() }
            }
        }

    private val STATES = setOf("todo", "in_progress", "done", "blocked")
}

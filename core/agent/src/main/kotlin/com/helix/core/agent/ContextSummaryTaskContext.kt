package com.helix.core.agent

import com.helix.core.model.ModelRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Read-only task data for the summarizer, never selected history or a new authority source. */
internal object ContextSummaryTaskContext {
    fun update(
        snapshot: ContextSourceSnapshot,
        selected: List<ContextSourceRow>,
        next: List<ContextSourceRow>,
        knownTurns: Set<String>,
        current: String,
    ): String {
        val changed = next.any { it.role == ModelRole.USER.name || (it.turnId != null && it.turnId !in knownTurns) }
        return if (changed) serialize(snapshot, selected + next) else current
    }

    fun serialize(
        snapshot: ContextSourceSnapshot,
        selected: List<ContextSourceRow>,
    ): String {
        val selectedIds = selected.map { it.id }.toSet()
        val relatedTurns = selected.mapNotNull { it.turnId }.toSet() + snapshot.currentTurnId
        val messages =
            snapshot.rows
                .filter { it.turnId in relatedTurns && it.role == ModelRole.USER.name && it.id !in selectedIds }
                .mapNotNull { row ->
                    row.mappedMessages().singleOrNull()?.takeIf { it.role == ModelRole.USER }?.let { message ->
                        buildJsonObject {
                            put("id", row.id)
                            put("sequence", row.sequence)
                            put("turnId", row.turnId)
                            put(
                                "relationship",
                                if (row.turnId ==
                                    snapshot.currentTurnId
                                ) {
                                    "current_turn"
                                } else {
                                    "selected_history_turn"
                                },
                            )
                            put("role", ModelRole.USER.name)
                            put("content", message.text)
                            put("attachmentCount", row.attachmentCount)
                            if (row.attachmentCount > 0) put("attachmentContent", "not_included_in_summary_request")
                        }
                    }
                }
        if (messages.isEmpty()) return ""
        return buildJsonObject {
            put("contextOnly", true)
            put("scope", "retained_task_user_messages")
            put("messages", JsonArray(messages))
        }.toString() + "\n"
    }
}

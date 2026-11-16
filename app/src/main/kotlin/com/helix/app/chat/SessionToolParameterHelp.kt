package com.helix.app.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Input provenance stays next to the session contract, not hidden in a general tool prompt. */
internal object SessionToolParameterHelp {
    fun annotate(properties: JsonObject): JsonObject =
        JsonObject(
            properties.mapValues { (key, value) ->
                val help = descriptions[key]
                if (help == null) value else JsonObject(value.jsonObject + ("description" to JsonPrimitive(help)))
            },
        )

    private val descriptions =
        mapOf(
            "query" to "Literal case-insensitive text to find, not a regular expression. " +
                "Keep it unchanged while paging.",
            "messageId" to "Exact messageId returned by session_history/search in the same source scope.",
            "contentHash" to "Exact contentHash returned with that message; do not compute or guess a new revision.",
            "referenceOrdinal" to "Copy only when the history entry supplies referenceOrdinal for a saved reference.",
            "offset" to "Zero-based Unicode code-point offset. Use 0 for the first page, then the returned nextOffset.",
            "targetSessionId" to "Omit for this conversation. Otherwise copy an explicitly authorized session ID " +
                "from session_list/status and keep the same target for subsequent reads. " +
                "Do not combine with shared reference fields.",
            "referenceMessageId" to "Exact saved reference message ID in this conversation. " +
                "Supply together with referenceHash to read only that fixed shared source.",
            "referenceHash" to "Exact hash of the shared reference manifest, paired with referenceMessageId. " +
                "This is not the target message contentHash.",
            "expectedTitle" to "Exact current title from session_status, including an empty title if returned.",
            "title" to "New conversation title. Does not change model, workspace or permissions.",
            "expectedArchived" to "Current archived boolean from session_status, not the desired new value.",
        )
}

package com.helix.core.storage.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Preserve edges without guessing whether an absent target was deleted or belongs to another session. */
internal class SessionExportReferences(
    private val contains: (String) -> Boolean,
) {
    fun describe(
        type: SessionExportType,
        data: JsonObject,
    ): JsonObject {
        val references =
            FIELDS[type]
                .orEmpty()
                .map { (field, prefix) ->
                    edge(
                        field,
                        prefix,
                        data[field] as? JsonPrimitive,
                        (data["omittedFields"] as? JsonObject)?.containsKey(
                            if (field == "contentId") "contentRef" else field,
                        ) == true,
                    )
                }.toMutableList()
        if (type == SessionExportType.USAGE && data.containsKey("correlationId")) {
            references += edge("correlationId", "model_call:", data["correlationId"] as? JsonPrimitive)
        }
        if (type == SessionExportType.COMPACTION) {
            (data["preservedMessageIds"] as? JsonArray)?.forEachIndexed { index, id ->
                require(id is JsonPrimitive && id.isString) { "Invalid preserved message identity" }
                references += edge("preservedMessageIds[$index]", "message:", id)
            }
        }
        return JsonObject(data + ("references" to JsonArray(references)))
    }

    private fun edge(
        field: String,
        prefix: String,
        value: JsonPrimitive?,
        omitted: Boolean = false,
    ): JsonObject {
        val target = value?.contentOrNull?.let { prefix + it }
        return buildJsonObject {
            put("field", field)
            put("targetRecordId", target?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "status",
                when {
                    omitted -> "omitted_limit"
                    target == null -> "not_recorded"
                    contains(target) -> "included"
                    else -> "not_in_selected_snapshot"
                },
            )
        }
    }

    companion object {
        private val FIELDS =
            mapOf(
                SessionExportType.TURN to listOf("sessionId" to "session:"),
                SessionExportType.MESSAGE to listOf("sessionId" to "session:", "turnId" to "turn:", "contentId" to ""),
                SessionExportType.MODEL_CALL to listOf("turnId" to "turn:"),
                SessionExportType.TOOL_CALL to listOf("turnId" to "turn:", "modelCallId" to "model_call:"),
                SessionExportType.TOOL_RESULT to listOf("toolCallId" to "tool_call:", "contentId" to ""),
                SessionExportType.EXECUTION to listOf("toolCallId" to "tool_call:"),
                SessionExportType.APPROVAL to listOf("toolCallId" to "tool_call:"),
                SessionExportType.ARTIFACT to listOf("sessionId" to "session:", "turnId" to "turn:"),
                SessionExportType.ATTACHMENT to listOf("messageId" to "message:", "artifactId" to "artifact:"),
                SessionExportType.GOAL_RUN to listOf("goalId" to "usage:goal:"),
                SessionExportType.GOAL_BINDING to listOf("turnId" to "turn:", "runId" to "goal_run:"),
                SessionExportType.COMPACTION to
                    listOf("messageId" to "message:", "contentId" to "", "sourceCallId" to "model_call:"),
            )
    }
}

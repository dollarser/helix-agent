package com.helix.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** A prior host tool observation, never a claim that its connection/display is still alive. */
internal data class ToolObservationPresentation(
    val background: Boolean,
    val packageName: String,
    val unsettled: Boolean,
)

@Suppress("ReturnCount") // Malformed or absent evidence never becomes a current target.
internal fun toolObservationPresentation(row: ToolTimelineRow): ToolObservationPresentation? {
    if (row.toolName != "ui.device") return null
    val body =
        try {
            Json.parseToJsonElement(row.resultSummary ?: return null) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
    val display = body["displayId"]?.let { it as? JsonPrimitive }?.intOrNull ?: return null
    val route = body["executionDisplay"]?.let { it as? JsonPrimitive }?.contentOrNull
    if (display < 0 || route != if (display == 0) "main" else "background") return null
    val target = body["packageName"]?.let { it as? JsonPrimitive }?.contentOrNull ?: return null
    if (!target.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) return null
    return ToolObservationPresentation(display != 0, target, false)
}

@Suppress("ReturnCount") // Only registered observation fields from supported UI tools carry this state.
internal fun toolHasUnsettledObservation(row: ToolTimelineRow): Boolean {
    if (row.toolName !in setOf("ui.set_text", "ui.scroll")) return false
    val body =
        try {
            Json.parseToJsonElement(row.resultSummary ?: return false) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
    return (body["observationStatus"] as? JsonPrimitive)?.contentOrNull == "UNSETTLED"
}

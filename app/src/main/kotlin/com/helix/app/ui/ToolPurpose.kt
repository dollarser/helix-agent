package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Describes the actual operation and target, never guesses a model's unstated intent. */
internal object ToolPurpose {
    @Composable
    fun text(
        name: String,
        arguments: String,
    ): String {
        val label = stringResource(actionRes(name))
        return target(arguments)?.let { "$label · $it" } ?: label
    }

    private fun actionRes(name: String): Int =
        when {
            name == "write" -> R.string.tool_purpose_write
            name == "edit" -> R.string.tool_purpose_edit
            isRead(name) -> R.string.tool_purpose_read
            isInspect(name) -> R.string.tool_purpose_inspect
            name.endsWith(".find") || name.endsWith(".search") -> R.string.tool_purpose_search
            name.startsWith("browser.") || name.startsWith("ui.") -> R.string.tool_purpose_interact
            name == "bash" || name == "code.javascript.run" -> R.string.tool_purpose_run
            else -> R.string.tool_purpose_execute
        }

    private fun isRead(name: String): Boolean =
        name == "read" || name.endsWith(".read") || name.endsWith(".read_resource")

    private fun isInspect(name: String): Boolean =
        name.endsWith(".list") || name.endsWith(".stat") || name.endsWith(".info") ||
            name.endsWith(".status") || name.endsWith(".query")

    fun target(arguments: String): String? {
        val fields =
            try {
                Json.parseToJsonElement(arguments) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            } ?: return null
        return listOf("destination", "path", "source", "tabId", "name")
            .firstNotNullOfOrNull { key ->
                (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            }?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.take(120)
    }
}

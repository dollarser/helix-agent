package com.helix.feature.conversation.presentation

import com.helix.core.policy.DiagnosticRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A disposable display projection. Original arguments, results and model context are never rewritten. */
data class ToolPayloadPresentation(
    val text: String,
    val incomplete: Boolean = false,
)

fun presentToolPayload(
    toolName: String,
    raw: String,
    label: (String) -> String = { it },
): ToolPayloadPresentation {
    val redacted = DiagnosticRedaction.redact(raw)
    val parsed =
        try {
            Json.parseToJsonElement(redacted)
        } catch (_: IllegalArgumentException) {
            null
        }
    if (parsed == null) {
        // The persisted bounded JSON summary is not a complete document. Never show its raw prefix.
        val clippedJson = redacted.trimStart().firstOrNull() in setOf('{', '[') && redacted.endsWith("…")
        return ToolPayloadPresentation(if (clippedJson) "" else redacted, clippedJson)
    }
    return ToolPayloadPresentation(renderPayload(withoutMetadata(toolName, parsed, emptyList()), label))
}

private fun withoutMetadata(
    name: String,
    value: JsonElement,
    path: List<String>,
): JsonElement =
    when (value) {
        is JsonObject -> {
            JsonObject(
                value.filterKeys { it !in metadataFields(name, path) }.mapValues { (key, child) ->
                    withoutMetadata(name, child, path + key)
                },
            )
        }

        is JsonArray -> {
            JsonArray(value.map { withoutMetadata(name, it, path + "*") })
        }

        else -> {
            value
        } // Text, instructions, code, errors and file contents remain opaque strings.
    }

private fun metadataFields(
    name: String,
    path: List<String>,
): Set<String> =
    when (name) {
        in SKILL_TOOLS -> skillMetadata(name, path)
        in UI_TOOLS -> uiMetadata(path)
        else -> emptySet()
    }

private fun skillMetadata(
    name: String,
    path: List<String>,
): Set<String> =
    when {
        path.isEmpty() -> {
            setOf("snapshotHash", "ownerId", "sessionId") +
                if (name == "skills_read_resource") setOf("sha256") else emptySet()
        }

        name == "skills_list" && path == listOf("entries", "*") -> {
            setOf("snapshotHash", "ownerId")
        }

        name == "skills_scripts" && path == listOf("scripts", "*") -> {
            setOf("sha256")
        }

        else -> {
            emptySet()
        }
    }

private fun uiMetadata(path: List<String>): Set<String> =
    when {
        path.isEmpty() || path == listOf("observation") -> UI_IDENTITY
        path in NODE_PATHS || path in NODE_CONTEXT_PATHS -> NODE_IDENTITY
        path.lastOrNull() == "longClick" && path.dropLast(1) in NODE_PATHS -> setOf("token")
        else -> emptySet()
    }

private fun renderPayload(
    value: JsonElement,
    label: (String) -> String,
): String =
    when (value) {
        is JsonObject -> {
            value.entries
                .mapNotNull { (key, child) ->
                    val text = renderPayload(child, label)
                    when {
                        text.isNotEmpty() -> "${label(key)}: ${if ('\n' in text) "\n" else ""}$text"
                        child is JsonPrimitive && child.isString -> "${label(key)}: \"\""
                        else -> null
                    }
                }.joinToString("\n")
        }

        is JsonArray -> {
            if (value.isEmpty()) label("empty") else value.joinToString("\n\n") { "• ${renderPayload(it, label)}" }
        }

        JsonNull -> {
            label("none")
        }

        is JsonPrimitive -> {
            renderPrimitive(value, label)
        }
    }

private fun renderPrimitive(
    value: JsonPrimitive,
    label: (String) -> String,
): String =
    if (value.isString) {
        value.content
    } else {
        when (value.content) {
            "true" -> label("yes")
            "false" -> label("no")
            else -> value.content
        }
    }

private val SKILL_TOOLS =
    setOf(
        "skills_list",
        "skills_read",
        "skills_read_resource",
        "skills_scripts",
        "skills_enable",
        "skills_disable",
        "skills_remove",
        "skills_run",
    )
private val UI_TOOLS =
    setOf(
        "ui_apps",
        "ui_launch",
        "ui_snapshot",
        "ui_screenshot",
        "ui_find",
        "ui_click",
        "ui_click_match",
        "ui_long_click",
        "ui_set_text",
        "ui_scroll",
        "ui_back",
        "ui_wait",
        "ui_device",
        "ui_gesture",
        "ui_system",
        "ui_set_progress",
    )
private val UI_IDENTITY =
    setOf(
        "token",
        "frame",
        "windowId",
        "displayId",
        "generation",
        "observedAtMillis",
        "suggestedClickToken",
        "sha256",
        "originatingHelixPackage",
    )
private val NODE_IDENTITY = setOf("token", "parentToken", "clickTargetToken", "viewId", "className", "depth")
private val NODE_PATHS = setOf(listOf("nodes", "*"), listOf("observation", "nodes", "*"))

private val NODE_CONTEXT_PATHS = NODE_PATHS.map { it + listOf("ancestorContexts", "*") }.toSet()

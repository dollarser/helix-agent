package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Collections

/** Detach mutable producer collections; a published contract cannot change behind its BindingRef. */
internal fun ToolDescriptor.snapshot(): ToolDescriptor =
    copy(
        inputSchema = inputSchema.snapshotJson() as JsonObject,
        outputSchema = outputSchema.snapshotJson() as JsonObject,
        requiredCapabilities = Collections.unmodifiableSet(requiredCapabilities.toMutableSet()),
        origin =
            (origin as? ToolOrigin.McpOrigin)?.let {
                it.copy(serverProvidedHints = Collections.unmodifiableMap(it.serverProvidedHints.toMutableMap()))
            } ?: origin,
    )

private fun JsonElement.snapshotJson(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(Collections.unmodifiableMap(mapValues { it.value.snapshotJson() }))
        is JsonArray -> JsonArray(Collections.unmodifiableList(map { it.snapshotJson() }))
        else -> this
    }

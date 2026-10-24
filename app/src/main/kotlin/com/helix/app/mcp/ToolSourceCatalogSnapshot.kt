package com.helix.app.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Metadata from the actually admitted descriptor window, not another tool registry. */
internal data class ToolSourceCatalogEntry(
    val source: String,
    val categories: List<String>,
    val revision: String,
) {
    fun facts() =
        buildJsonObject {
            put("source", source)
            put("categories", JsonArray(categories.map(::JsonPrimitive)))
        }
}

internal data class ToolSourceCatalogSnapshot(
    val entries: List<ToolSourceCatalogEntry>,
    val omittedAtAcquisition: Int,
)

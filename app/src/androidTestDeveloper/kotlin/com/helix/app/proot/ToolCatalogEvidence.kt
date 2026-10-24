package com.helix.app.proot

import com.helix.app.application.context.PromptContentContributions
import com.helix.app.mcp.ToolSourceCatalogSnapshot
import com.helix.core.agent.PromptRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Synthetic diagnostics use the production content mapper, not a retired aggregate protocol. */
internal fun ToolSourceCatalogSnapshot?.modelContext(tools: Set<String>): String =
    PromptRegistry()
        .also { registry ->
            PromptContentContributions.sections(emptyList(), this, null, tools).forEach(registry::register)
        }.assemble()

internal fun ToolSourceCatalogSnapshot?.diagnosticJson(): JsonElement =
    this?.let { snapshot ->
        buildJsonObject {
            put("entries", JsonArray(snapshot.entries.map { it.facts() }))
            put("omittedAtAcquisition", snapshot.omittedAtAcquisition)
        }
    } ?: JsonNull

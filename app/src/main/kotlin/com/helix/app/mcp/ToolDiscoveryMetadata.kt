package com.helix.app.mcp

import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Read-only discovery data from the registered contract. Never policy or authority. */
internal data class ToolDiscoveryMetadata(
    val source: String,
    val category: String,
    val searchText: String,
) {
    companion object {
        private val aliases = loadAliases()
        private val categoryAliases =
            aliases.getValue("categories").jsonObject.mapValues { it.value.jsonPrimitive.content }
        private val operationAliases =
            aliases.getValue("operations").jsonObject.mapValues { it.value.jsonPrimitive.content }

        private fun loadAliases(): kotlinx.serialization.json.JsonObject {
            val stream = ToolDiscoveryMetadata::class.java.getResourceAsStream("/tool-discovery-aliases.json")
            val text =
                requireNotNull(stream) { "Missing packaged tool discovery aliases" }
                    .bufferedReader()
                    .use { it.readText() }
            return Json.parseToJsonElement(text).jsonObject
        }

        fun of(tool: ToolDescriptor): ToolDiscoveryMetadata {
            val source =
                when (val origin = tool.origin) {
                    ToolOrigin.BuiltInOrigin -> "helix"
                    is ToolOrigin.PluginOrigin -> "plugin:${origin.pluginId}"
                    is ToolOrigin.McpOrigin -> "mcp:${origin.serverId}"
                    is ToolOrigin.A2aOrigin -> "a2a:${origin.agentId}"
                }
            val name = tool.name.value
            val category =
                when {
                    name in setOf("read", "write", "edit", "view_image") || name.startsWith("files.") -> "files"
                    name.startsWith("goal.") -> "goals"
                    name == "ask_user" -> "user"
                    else -> name.substringBefore('.')
                }
            val aliases = categoryAliases[category].orEmpty()
            val operation = operationAliases[name.substringAfterLast('.')].orEmpty()
            val fields = tool.inputSchema["properties"] as? kotlinx.serialization.json.JsonObject
            return ToolDiscoveryMetadata(
                source,
                category,
                listOf(
                    source,
                    category,
                    aliases,
                    operation,
                    fields
                        ?.keys
                        ?.sorted()
                        ?.joinToString(" ")
                        .orEmpty(),
                ).joinToString(" ").lowercase(),
            )
        }
    }
}

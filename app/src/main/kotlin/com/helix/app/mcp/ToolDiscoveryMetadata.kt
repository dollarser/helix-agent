package com.helix.app.mcp

import com.helix.app.plugin.nativePluginName
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
    val fieldNames: Set<String>,
) {
    companion object {
        private val aliases = loadAliases()
        private val categoryAliases =
            aliases.getValue("categories").jsonObject.mapValues { it.value.jsonPrimitive.content }
        private val operationAliases =
            aliases.getValue("operations").jsonObject.mapValues { it.value.jsonPrimitive.content }
        private val namespaceAliases =
            aliases.getValue("namespaces").jsonObject.mapValues { it.value.jsonPrimitive.content }

        private fun loadAliases(): kotlinx.serialization.json.JsonObject {
            val stream = ToolDiscoveryMetadata::class.java.getResourceAsStream("/tool-discovery-aliases.json")
            val text =
                requireNotNull(stream) { "Missing packaged tool discovery aliases" }
                    .bufferedReader()
                    .use { it.readText() }
            return Json.parseToJsonElement(text).jsonObject
        }

        /** Index descriptions and nested parameter names, not values or arbitrary extension metadata. */
        private fun parameterText(schema: kotlinx.serialization.json.JsonObject): Pair<Set<String>, List<String>> {
            val names = linkedSetOf<String>()
            val descriptions = mutableListOf<String>()
            val pending = ArrayDeque<kotlinx.serialization.json.JsonObject>()
            pending.add(schema)
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                (node["description"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.let(descriptions::add)
                (node["properties"] as? kotlinx.serialization.json.JsonObject)?.let { properties ->
                    names.addAll(properties.keys)
                    properties.values
                        .filterIsInstance<kotlinx.serialization.json.JsonObject>()
                        .forEach(pending::addLast)
                }
                for (key in listOf("items", "additionalProperties")) {
                    (node[key] as? kotlinx.serialization.json.JsonObject)?.let(pending::addLast)
                }
            }
            return names.mapTo(linkedSetOf()) { it.lowercase() } to descriptions
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
            val category = tool.discovery.category ?: "other"
            val aliases = categoryAliases[category].orEmpty()
            val operation = operationAliases.filterKeys { name == it || name.endsWith("_$it") }.values.joinToString(" ")
            val parameters = parameterText(tool.inputSchema)
            return ToolDiscoveryMetadata(
                source,
                category,
                listOf(
                    source,
                    tool.discovery.keywords.joinToString(" "),
                    parameters.second.joinToString(" "),
                    nativePluginName(tool.origin)?.aliases.orEmpty().joinToString(" "),
                    category,
                    aliases,
                    operation,
                    namespaceAliases[category].orEmpty(),
                ).joinToString(" ").lowercase(),
                parameters.first,
            )
        }
    }
}

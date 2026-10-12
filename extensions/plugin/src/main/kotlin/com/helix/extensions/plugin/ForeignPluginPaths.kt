package com.helix.extensions.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** External component paths are package-relative data; they never trigger downloads or setup. */
internal object ForeignPluginPaths {
    fun skillContent(
        files: Map<String, ByteArray>,
        root: String,
        credentialConfigs: Set<String>,
        diagnostics: MutableList<String>,
        name: String,
    ): Map<String, ByteArray> {
        if (credentialConfigs.any { root.isEmpty() || it.startsWith("$root/") }) {
            diagnostics += "SKILL_CREDENTIAL_CONFIG_OMITTED:$name"
        }
        return files
            .filterKeys { it !in credentialConfigs && (root.isEmpty() || it.startsWith("$root/")) }
            .mapKeys { (path, _) -> if (root.isEmpty()) path else path.removePrefix("$root/") }
            .mapValues { it.value.copyOf() }
    }

    fun skillRoots(
        value: JsonElement?,
        diagnostics: MutableList<String>,
    ): List<String> {
        val roots = mutableListOf("skills")
        values(value).forEach { item ->
            if (item is JsonPrimitive && item.isString) {
                val path = item.content.removePrefix("./").removeSuffix("/")
                roots += if (path.isEmpty() || path == ".") "" else PluginPackageReader.safePath(path)
            } else {
                diagnostics += "UNSUPPORTED_SKILL_REFERENCE"
            }
        }
        return roots.distinct()
    }

    fun mcpConfigs(
        value: JsonElement?,
        files: Map<String, ByteArray>,
        configs: MutableMap<String, JsonObject>,
        diagnostics: MutableList<String>,
        parse: (ByteArray) -> JsonObject,
    ) {
        values(value).forEachIndexed { index, item ->
            when {
                item is JsonObject -> {
                    configs["inline-$index"] = item
                }

                item is JsonPrimitive && item.isString -> {
                    val reference = item.content
                    if (reference.startsWith("https://") || reference.endsWith(".mcpb") || reference.endsWith(".dxt")) {
                        diagnostics += "UNSUPPORTED_MCP_CONFIG_REFERENCE"
                    } else {
                        val path = PluginPackageReader.safePath(reference.removePrefix("./"))
                        configs.remove(path)
                        configs[path] = parse(requireNotNull(files[path]) { "CONNECTOR_MISSING_MCP_CONFIG" })
                    }
                }

                else -> {
                    diagnostics += "UNSUPPORTED_MCP_CONFIG_REFERENCE"
                }
            }
        }
    }

    private fun values(value: JsonElement?): List<JsonElement> =
        when (value) {
            null -> emptyList()
            is JsonArray -> value.toList()
            else -> listOf(value)
        }
}

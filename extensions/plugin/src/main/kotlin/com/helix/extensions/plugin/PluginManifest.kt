package com.helix.extensions.plugin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Agent Plugins 1.0 portable manifest fields Helix consumes today. */
data class PluginManifest(
    val schema: String,
    val name: String,
    val version: String,
    val description: String,
    val extensions: JsonObject,
    val diagnostics: List<String> = emptyList(),
) {
    /** Host-private runtime binding. It is never interpreted as an Agent Plugins portable field. */
    val helixRuntimeId: String?
        get() =
            ((extensions[HELIX_EXTENSION_NAMESPACE] as? JsonObject)?.get("runtime") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content

    companion object {
        const val AGENT_PLUGINS_V1_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json"
        const val HELIX_EXTENSION_NAMESPACE = "com.helix.agent"
    }
}

/**
 * Bounded local parser for the v1 root manifest. Helix never downloads the schema while loading a
 * plugin; the canonical schema identifier selects this locally implemented contract.
 *
 * Agent Plugins v1 says unknown root fields are non-fatal and a non-object `extensions` value is
 * ignored. The portable component readers remain independent from this manifest parser.
 */
object PluginManifestReader {
    fun parse(bytes: ByteArray): PluginManifest = PortablePluginManifest.parse(PluginJson.parse(bytes))
}

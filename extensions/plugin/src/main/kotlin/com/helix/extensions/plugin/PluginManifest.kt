package com.helix.extensions.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The Agent Plugins 1.0 portable manifest fields Helix consumes today. */
data class PluginManifest(
    val schema: String,
    val name: String,
    val version: String,
    val description: String,
    val extensions: JsonObject,
) {
    /** Host-private runtime binding. It is never interpreted as an Agent Plugins portable field. */
    val helixRuntimeId: String?
        get() =
            (extensions[HELIX_EXTENSION_NAMESPACE] as? JsonObject)
                ?.get("runtime")
                ?.jsonPrimitive
                ?.contentOrNull

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
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private val pluginName = Regex("[a-z0-9][a-z0-9.-]{0,127}")
    private val version = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")

    fun parse(bytes: ByteArray): PluginManifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "plugin manifest size is invalid" }
        val root =
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                ?: throw IllegalArgumentException("plugin manifest must be a JSON object")
        val schema = requiredString(root, "\$schema", 256)
        require(schema == PluginManifest.AGENT_PLUGINS_V1_SCHEMA) { "unsupported Agent Plugins schema: $schema" }
        val name = requiredString(root, "name", 128)
        require(pluginName.matches(name)) { "plugin name is invalid" }
        val pluginVersion = requiredString(root, "version", 64)
        require(version.matches(pluginVersion)) { "plugin version is invalid" }
        val description = requiredString(root, "description", 1_024)
        val extensions = root["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val manifest = PluginManifest(schema, name, pluginVersion, description, extensions)
        manifest.helixRuntimeId?.let { runtime ->
            require(pluginName.matches(runtime)) { "Helix plugin runtime id is invalid" }
        }
        return manifest
    }

    private fun requiredString(
        root: JsonObject,
        key: String,
        maxLength: Int,
    ): String {
        val value = root[key]?.jsonPrimitive?.contentOrNull
        require(!value.isNullOrBlank() && value.length <= maxLength) { "plugin manifest field $key is invalid" }
        require(value.none { it.code < 0x20 }) { "plugin manifest field $key contains control characters" }
        return value
    }
}

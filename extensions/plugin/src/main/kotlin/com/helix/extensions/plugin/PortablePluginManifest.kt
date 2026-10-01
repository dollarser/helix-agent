package com.helix.extensions.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Agent Plugins 1.0 normative text, rather than another client's manifest schema. */
internal object PortablePluginManifest {
    private val fields =
        setOf(
            "\$schema",
            "name",
            "version",
            "description",
            "author",
            "homepage",
            "repository",
            "license",
            "keywords",
            "extensions",
        )
    private val namePattern = Regex("[a-z0-9](?:[a-z0-9.-]{0,62}[a-z0-9])?")

    fun validName(name: String): Boolean = namePattern.matches(name) && "--" !in name && ".." !in name

    fun parse(root: JsonObject): PluginManifest {
        val schema = PluginJson.string(root, "\$schema")
        require(schema == PluginManifest.AGENT_PLUGINS_V1_SCHEMA) { "CONNECTOR_UNSUPPORTED_PLUGIN_SCHEMA" }
        val name = PluginJson.string(root, "name")
        require(validName(name)) { "PLUGIN_NAME_INVALID" }
        listOf("version", "description", "homepage", "repository", "license").filter { it in root }.forEach {
            PluginJson.string(root, it)
        }
        validateAuthor(root)
        if ("keywords" in root) {
            val keywords = root["keywords"] as? JsonArray ?: throw IllegalArgumentException("PLUGIN_KEYWORDS_INVALID")
            require(keywords.all { it is JsonPrimitive && it.isString }) { "PLUGIN_KEYWORDS_INVALID" }
        }
        val diagnostics =
            root.keys
                .filter { it !in fields }
                .map {
                    "PLUGIN_UNKNOWN_FIELD:${it.take(
                        80,
                    )}"
                }.toMutableList()
        val extensions = root["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        if ("extensions" in root && root["extensions"] !is JsonObject) diagnostics += "PLUGIN_EXTENSIONS_IGNORED"
        // Unknown namespaces are deliberately not validated. Even Helix data grants no runtime loading.
        return PluginManifest(
            schema,
            name,
            if ("version" in root) PluginJson.string(root, "version") else "",
            if ("description" in root) PluginJson.string(root, "description") else "",
            extensions,
            diagnostics,
        )
    }

    private fun validateAuthor(root: JsonObject) {
        if ("author" !in root) return
        val author = root["author"] as? JsonObject ?: throw IllegalArgumentException("PLUGIN_AUTHOR_INVALID")
        require(author.keys.all { it in setOf("name", "email", "url") }) { "PLUGIN_AUTHOR_INVALID" }
        author.keys.forEach { PluginJson.string(author, it) }
    }
}

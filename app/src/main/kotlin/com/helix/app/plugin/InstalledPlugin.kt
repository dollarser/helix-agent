package com.helix.app.plugin

import com.helix.extensions.plugin.PluginEndpoint
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class InstalledEndpoint(
    val id: String,
    val endpoint: PluginEndpoint,
)

/** Only the trusted APK composition root creates this component; portable imports never do. */
data class NativePluginComponent(
    val pluginId: String,
    val runtimeId: String,
)

data class InstalledPlugin(
    val id: String,
    val name: String,
    val source: String,
    val hash: String,
    val endpoints: List<InstalledEndpoint>,
    val skills: List<SkillKey>,
    val diagnostics: List<String>,
    val identity: String,
    val revision: Long,
    val sessionScoped: Boolean,
    val versionLabel: String? = null,
    val releaseNotes: String? = null,
    val enabled: Boolean = true,
    val native: NativePluginComponent? = null,
)

internal fun encode(record: InstalledPlugin): String =
    buildJsonObject {
        put("formatVersion", 1)
        put("identity", record.identity)
        put("revision", record.revision)
        put("sessionScoped", record.sessionScoped)
        put("enabled", record.enabled)
        record.native?.let {
            put(
                "native",
                buildJsonObject {
                    put("pluginId", it.pluginId)
                    put("runtimeId", it.runtimeId)
                },
            )
        }
        record.versionLabel?.let { put("versionLabel", it) }
        record.releaseNotes?.let { put("releaseNotes", it) }
        put("id", record.id)
        put("name", record.name)
        put("source", record.source)
        put("hash", record.hash)
        put(
            "endpoints",
            JsonArray(
                record.endpoints.map { server ->
                    buildJsonObject {
                        put("id", server.id)
                        put("name", server.endpoint.name)
                        put("url", server.endpoint.url)
                        put("credential", server.endpoint.needsCredential)
                        server.endpoint.authBindingHash?.let { put("authBindingHash", it) }
                    }
                },
            ),
        )
        put(
            "skills",
            JsonArray(
                record.skills.map { skill ->
                    buildJsonObject {
                        put("name", skill.name)
                        put("hash", skill.snapshotHash)
                    }
                },
            ),
        )
        put("diagnostics", JsonArray(record.diagnostics.map(::JsonPrimitive)))
    }.toString()

internal fun decode(text: String): InstalledPlugin {
    val obj = Json.parseToJsonElement(text).jsonObject
    require(obj["formatVersion"]?.jsonPrimitive?.content == "1") { "CONNECTOR_RECORD_VERSION" }

    fun value(key: String) = obj.getValue(key).jsonPrimitive.content
    return InstalledPlugin(
        value("id"),
        value("name"),
        value("source"),
        value("hash"),
        obj.getValue("endpoints").jsonArray.map { item ->
            val e = item.jsonObject
            InstalledEndpoint(
                e.getValue("id").jsonPrimitive.content,
                PluginEndpoint(
                    e.getValue("name").jsonPrimitive.content,
                    e.getValue("url").jsonPrimitive.content,
                    e.getValue("credential").jsonPrimitive.content == "true",
                    e["authBindingHash"]?.jsonPrimitive?.content,
                ),
            )
        },
        obj.getValue("skills").jsonArray.map { item ->
            val s = item.jsonObject
            SkillKey(
                SkillSource.USER_IMPORTED,
                s.getValue("name").jsonPrimitive.content,
                s.getValue("hash").jsonPrimitive.content,
            )
        },
        obj.getValue("diagnostics").jsonArray.map { it.jsonPrimitive.content },
        value("identity"),
        value("revision").toLong(),
        value("sessionScoped").toBooleanStrict(),
        obj["versionLabel"]?.jsonPrimitive?.content,
        obj["releaseNotes"]?.jsonPrimitive?.content,
        obj["enabled"]?.jsonPrimitive?.content?.toBooleanStrict() ?: true,
        (obj["native"] as? kotlinx.serialization.json.JsonObject)?.let {
            require(value("source") == "BUNDLED_PLUGIN") { "PLUGIN_NATIVE_SOURCE_INVALID" }
            NativePluginComponent(
                it.getValue("pluginId").jsonPrimitive.content,
                it.getValue("runtimeId").jsonPrimitive.content,
            )
        },
    )
}

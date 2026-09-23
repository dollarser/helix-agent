package com.helix.app.connector

import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import com.helix.extensions.skills.connector.ConnectorEndpoint
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
    val endpoint: ConnectorEndpoint,
)

data class InstalledConnector(
    val id: String,
    val name: String,
    val source: String,
    val hash: String,
    val endpoints: List<InstalledEndpoint>,
    val skills: List<SkillKey>,
    val diagnostics: List<String>,
    val identity: String = "legacy:$id",
    val revision: Long = 1,
    val sessionScoped: Boolean = true,
    val versionLabel: String? = null,
    val releaseNotes: String? = null,
)

internal fun encode(record: InstalledConnector): String =
    buildJsonObject {
        put("formatVersion", 1)
        put("identity", record.identity)
        put("revision", record.revision)
        put("sessionScoped", record.sessionScoped)
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

internal fun decode(text: String): InstalledConnector {
    val obj = Json.parseToJsonElement(text).jsonObject
    require(obj["formatVersion"]?.jsonPrimitive?.content == "1") { "CONNECTOR_RECORD_VERSION" }

    fun value(key: String) = obj.getValue(key).jsonPrimitive.content
    return InstalledConnector(
        value("id"),
        value("name"),
        value("source"),
        value("hash"),
        obj.getValue("endpoints").jsonArray.map { item ->
            val e = item.jsonObject
            InstalledEndpoint(
                e.getValue("id").jsonPrimitive.content,
                ConnectorEndpoint(
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
        obj["identity"]?.jsonPrimitive?.content ?: "legacy:" + value("id"),
        obj["revision"]?.jsonPrimitive?.content?.toLong() ?: 1,
        obj["sessionScoped"]?.jsonPrimitive?.content != "false",
        obj["versionLabel"]?.jsonPrimitive?.content,
        obj["releaseNotes"]?.jsonPrimitive?.content,
    )
}

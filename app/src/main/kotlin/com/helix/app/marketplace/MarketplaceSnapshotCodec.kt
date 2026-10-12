package com.helix.app.marketplace

import com.helix.extensions.plugin.MarketplacePluginEntry
import com.helix.extensions.plugin.PluginEndpoint
import com.helix.extensions.plugin.PluginMarketplace
import com.helix.extensions.plugin.PluginPackage
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.extensions.plugin.PluginSkill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** Persist the prepared import projection, never foreign MCP headers, tokens or auth configuration. */
internal object MarketplaceSnapshotCodec {
    fun encode(catalog: PluginMarketplace): ByteArray {
        val payload =
            buildJsonObject {
                put("name", JsonPrimitive(catalog.name))
                put("format", JsonPrimitive(catalog.format))
                put("hash", JsonPrimitive(catalog.contentHash))
                put(
                    "entries",
                    JsonArray(
                        catalog.entries.map { entry ->
                            buildJsonObject {
                                put("name", JsonPrimitive(entry.name))
                                put("description", JsonPrimitive(entry.description))
                                put("version", primitive(entry.version))
                                put("issue", primitive(entry.issue))
                                put("bundle", entry.bundle?.let(::bundle) ?: JsonNull)
                            }
                        },
                    ),
                )
            }
        return buildJsonObject {
            put("payload", payload)
            put("checksum", JsonPrimitive(checksum(payload)))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): PluginMarketplace {
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val payload = root.getValue("payload").jsonObject
        require(root.getValue("checksum").jsonPrimitive.content == checksum(payload)) { "MARKETPLACE_SNAPSHOT_CORRUPT" }
        return PluginMarketplace(
            text(payload, "name"),
            text(payload, "format"),
            text(payload, "hash"),
            payload.getValue("entries").jsonArray.map { item ->
                val entry = item.jsonObject
                MarketplacePluginEntry(
                    text(entry, "name"),
                    text(entry, "description"),
                    nullable(entry, "version"),
                    entry["bundle"]?.takeIf { it != JsonNull }?.jsonObject?.let(::decodeBundle),
                    nullable(entry, "issue"),
                )
            },
        )
    }

    private fun bundle(value: PluginPackage): JsonObject =
        buildJsonObject {
            put("name", JsonPrimitive(value.name))
            put("source", JsonPrimitive(value.source))
            put("hash", JsonPrimitive(value.contentHash))
            put("version", primitive(value.versionLabel))
            put("notes", primitive(value.releaseNotes))
            put("diagnostics", JsonArray(value.diagnostics.map(::JsonPrimitive)))
            put(
                "endpoints",
                JsonArray(
                    value.endpoints.map { endpoint ->
                        buildJsonObject {
                            put("name", JsonPrimitive(endpoint.name))
                            put("url", JsonPrimitive(endpoint.url))
                            put("needsCredential", JsonPrimitive(endpoint.needsCredential))
                            put("authHash", primitive(endpoint.authBindingHash))
                        }
                    },
                ),
            )
            put(
                "skills",
                JsonArray(
                    value.skills.map { skill ->
                        buildJsonObject {
                            put("directory", JsonPrimitive(skill.directory))
                            put(
                                "files",
                                buildJsonObject {
                                    skill.files.forEach { (path, bytes) ->
                                        put(path, JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
                                    }
                                },
                            )
                        }
                    },
                ),
            )
        }

    private fun decodeBundle(value: JsonObject): PluginPackage =
        PluginPackage(
            name = text(value, "name"),
            source = text(value, "source"),
            contentHash = text(value, "hash"),
            endpoints =
                value.getValue("endpoints").jsonArray.map { item ->
                    val endpoint = item.jsonObject
                    PluginEndpoint(
                        text(endpoint, "name"),
                        text(endpoint, "url"),
                        endpoint.getValue("needsCredential").jsonPrimitive.boolean,
                        nullable(endpoint, "authHash"),
                    )
                },
            skills =
                value.getValue("skills").jsonArray.map { item ->
                    val skill = item.jsonObject
                    val files =
                        skill.getValue("files").jsonObject.mapValues { (path, content) ->
                            PluginPackageReader.safePath(path)
                            Base64.getDecoder().decode(content.jsonPrimitive.content)
                        }
                    require(files.values.sumOf { it.size.toLong() } <= PluginPackageReader.MAX_BYTES) {
                        "CONNECTOR_TOO_LARGE"
                    }
                    require(files.values.all { it.size <= PluginPackageReader.MAX_FILE_BYTES }) {
                        "CONNECTOR_TOO_LARGE"
                    }
                    PluginSkill(text(skill, "directory"), files)
                },
            diagnostics = value.getValue("diagnostics").jsonArray.map { it.jsonPrimitive.content },
            versionLabel = nullable(value, "version"),
            releaseNotes = nullable(value, "notes"),
        )

    private fun checksum(value: JsonObject): String =
        PluginPackageReader.digest(mapOf("projection" to value.toString().toByteArray(Charsets.UTF_8)))

    private fun text(
        value: JsonObject,
        key: String,
    ): String = value.getValue(key).jsonPrimitive.content

    private fun nullable(
        value: JsonObject,
        key: String,
    ): String? = value[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    private fun primitive(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
}

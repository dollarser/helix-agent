package com.helix.app.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal object DirectorySyncCodec {
    fun encode(rules: List<DirectorySyncRule>): String =
        buildJsonObject {
            put("version", 1)
            put(
                "rules",
                JsonArray(
                    rules.map { rule ->
                        buildJsonObject {
                            put("id", rule.id)
                            put("left", root(rule.left))
                            put("right", root(rule.right))
                            put("enabled", rule.enabled)
                            put("status", rule.status)
                            put("updatedAt", rule.updatedAt)
                            put("copied", rule.copied)
                            put("conflicts", JsonArray(rule.conflicts.map(::JsonPrimitive)))
                            put(
                                "base",
                                JsonObject(
                                    rule.base.mapValues { (_, node) ->
                                        buildJsonObject {
                                            put("hash", node.hash)
                                            put("directory", node.directory)
                                        }
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
        }.toString()

    fun decode(raw: String): List<DirectorySyncRule> {
        val archive = Json.parseToJsonElement(raw).jsonObject
        require(archive.getValue("version").jsonPrimitive.int == 1) { "SYNC_FORMAT" }
        return archive.getValue("rules").jsonArray.map { element ->
            val row = element.jsonObject
            val base =
                row.getValue("base").jsonObject.mapValues { (_, element) ->
                    val node = element.jsonObject
                    val hash = node.getValue("hash").jsonPrimitive.content
                    val directory = node.getValue("directory").jsonPrimitive.boolean
                    require(if (directory) hash == "directory" else hash.matches(Regex("[0-9a-f]{64}")))
                    SyncNode(hash, directory)
                }
            val status = row.getValue("status").jsonPrimitive.content
            require(status in setOf("PAUSED", "READY", "RUNNING", "REVIEW", "CONFLICT", "COMPLETE"))
            DirectorySyncRule(
                row.getValue("id").jsonPrimitive.content,
                readRoot(row.getValue("left").jsonObject),
                readRoot(row.getValue("right").jsonObject),
                row.getValue("enabled").jsonPrimitive.boolean,
                status,
                base,
                row.getValue("conflicts").jsonArray.map { it.jsonPrimitive.content },
                row.getValue("updatedAt").jsonPrimitive.long,
                row.getValue("copied").jsonPrimitive.int,
            )
        }
    }

    private fun root(value: SyncRoot): JsonObject =
        buildJsonObject {
            put("scope", value.scope)
            put("path", value.path)
        }

    private fun readRoot(row: JsonObject) =
        SyncRoot(row.getValue("scope").jsonPrimitive.content, row.getValue("path").jsonPrimitive.content)
}

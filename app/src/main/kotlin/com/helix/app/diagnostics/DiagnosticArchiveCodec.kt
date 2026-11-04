package com.helix.app.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Explicit bounded format; no reflective serializers or permissive legacy readers. */
internal object DiagnosticArchiveCodec {
    fun record(value: DiagnosticRecord): String =
        buildJsonObject {
            put("formatVersion", 1)
            put("id", value.id)
            put("owner", value.owner?.let(::JsonPrimitive) ?: JsonNull)
            put("kind", value.kind.name)
            put("event", value.event)
            put("createdAt", value.createdAt)
            put("body", value.body?.let(::JsonPrimitive) ?: JsonNull)
            put("clearedAt", value.clearedAt?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()

    fun readRecord(text: String): DiagnosticRecord {
        val obj = Json.parseToJsonElement(text).jsonObject
        require(obj.keys == setOf("formatVersion", "id", "owner", "kind", "event", "createdAt", "body", "clearedAt"))
        require(obj.getValue("formatVersion").jsonPrimitive.long == 1L)
        return DiagnosticRecord(
            obj.getValue("id").jsonPrimitive.content,
            optional(obj, "owner"),
            DiagnosticKind.valueOf(obj.getValue("kind").jsonPrimitive.content),
            obj.getValue("event").jsonPrimitive.content,
            obj.getValue("createdAt").jsonPrimitive.long,
            optional(obj, "body"),
            obj
                .getValue("clearedAt")
                .takeUnless { it == JsonNull }
                ?.jsonPrimitive
                ?.long,
        )
    }

    fun config(value: DiagnosticArchiveConfig): String =
        buildJsonObject {
            put("formatVersion", value.formatVersion)
            put("normalDays", value.policy.normalDays)
            put("failureDays", value.policy.failureDays)
            put("budgetMiB", value.policy.budgetMiB)
            put("pinned", JsonArray(value.pinned.sorted().map(::JsonPrimitive)))
            put("lastSweep", value.lastSweep)
            put("dropped", value.dropped)
        }.toString()

    fun readConfig(text: String): DiagnosticArchiveConfig {
        val obj = Json.parseToJsonElement(text).jsonObject
        require(
            obj.keys ==
                setOf("formatVersion", "normalDays", "failureDays", "budgetMiB", "pinned", "lastSweep", "dropped"),
        )

        fun integer(key: String): Int {
            val number = obj.getValue(key).jsonPrimitive.long
            require(number in 0..Int.MAX_VALUE.toLong())
            return number.toInt()
        }
        return DiagnosticArchiveConfig(
            integer("formatVersion"),
            DiagnosticRetention(integer("normalDays"), integer("failureDays"), integer("budgetMiB")),
            obj
                .getValue("pinned")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
            obj.getValue("lastSweep").jsonPrimitive.long,
            obj.getValue("dropped").jsonPrimitive.long,
        )
    }

    private fun optional(
        obj: JsonObject,
        key: String,
    ): String? =
        obj
            .getValue(key)
            .takeUnless { it == JsonNull }
            ?.jsonPrimitive
            ?.content
}

package com.helix.tools.framework

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Bounded host-produced facts. No commands, output bodies, paths, credentials or execution authority. */
object JobObservationEvidence {
    fun encode(value: JobObservation): JsonObject =
        buildJsonObject {
            val binding = value.binding
            put("sessionId", binding.sessionId)
            put("turnId", binding.turnId)
            put("handle", binding.handle)
            put("providerRef", binding.providerRef)
            put("executionId", binding.executionId)
            put("generation", binding.generation)
            put("bindingHash", binding.bindingHash)
            put("state", value.state)
            put("terminal", value.terminal)
            put("requiresReview", value.requiresReview)
            put("settlementPending", value.settlementPending)
            put("revision", value.revision)
            put("observedAtMillis", value.observedAtMillis)
            put("exitCode", value.exitCode)
        }

    fun decode(value: JsonObject): JobObservation {
        require(value.toString().length <= 4096) { "JOB_OBSERVATION_LIMIT" }
        require(
            value.keys.containsAll(
                listOf(
                    "sessionId",
                    "turnId",
                    "handle",
                    "providerRef",
                    "executionId",
                    "generation",
                    "bindingHash",
                    "state",
                    "terminal",
                    "requiresReview",
                    "settlementPending",
                    "revision",
                    "observedAtMillis",
                ),
            ),
        ) { "JOB_OBSERVATION_FIELDS" }

        fun text(key: String) =
            value
                .getValue(key)
                .jsonPrimitive
                .also { require(it.isString) }
                .content
        return JobObservation(
            JobObservationBinding(
                text("sessionId"),
                text("turnId"),
                text("handle"),
                text("providerRef"),
                text("executionId"),
                text("generation"),
                text("bindingHash"),
            ),
            text("state"),
            value.getValue("terminal").jsonPrimitive.boolean,
            value.getValue("requiresReview").jsonPrimitive.boolean,
            value.getValue("settlementPending").jsonPrimitive.boolean,
            text("revision"),
            value.getValue("observedAtMillis").jsonPrimitive.long,
            value["exitCode"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** Normalize away wall-clock/elapsed values: a new observation time is not task progress. */
    fun fingerprint(payload: JsonObject): String {
        val rows = rows(payload)
        val normalized =
            rows
                .map { row ->
                    val value = row.jsonObject
                    val observation = decode(value)
                    JsonObject(encode(observation).filterKeys { it != "observedAtMillis" })
                }.sortedBy { it.toString() }
        return buildJsonObject {
            put("reason", payload.getValue("reason"))
            put("observations", JsonArray(normalized))
        }.toString()
    }

    private fun rows(payload: JsonObject): JsonArray {
        require(payload["reason"] is JsonPrimitive && payload.getValue("reason").jsonPrimitive.isString)
        val values = requireNotNull(payload["observations"] as? JsonArray)
        require(values.size <= 8)
        return values
    }

    /** Fresh, confirmed running observations are waiting, not completion and not evidence of progress. */
    fun waiting(payload: JsonObject): Boolean {
        val rows = rows(payload)
        val reason = payload.getValue("reason").jsonPrimitive.content
        if (reason !in setOf("WAIT_EXPIRED", "SNAPSHOT") || payload["completeSet"] != JsonPrimitive(true)) return false
        return rows.isNotEmpty() && rows.size <= 8 &&
            rows.all { row ->
                val value = row.jsonObject
                val observed = decode(value)
                value["stale"] == JsonPrimitive(false) && !observed.terminal && !observed.requiresReview &&
                    observed.state in setOf("RUNNING", "STARTING", "PREPARING", "ACCEPTED", "QUEUED")
            }
    }
}

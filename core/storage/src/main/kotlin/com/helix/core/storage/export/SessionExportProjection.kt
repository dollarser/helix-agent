package com.helix.core.storage.export

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Explicit public projection of the closed private snapshot, including unavailable historical facts. */
internal class SessionExportProjection(
    private val sanitizer: SessionExportSanitizer,
) {
    fun project(
        type: SessionExportType,
        row: JsonObject,
    ): JsonObject {
        val source = row.getValue("data").jsonObject
        val data = source.toMutableMap()
        data["omittedFields"] = row.getValue("omittedFields")
        source.forEach { (field, value) ->
            if (field in BODY_FIELDS && value != JsonNull) {
                data[field] = text(value.jsonPrimitive.content, field in STRUCTURED_FIELDS)
            }
        }
        source["contentRef"]?.let { value ->
            data.remove("contentRef")
            data["contentId"] =
                value.jsonPrimitive.contentOrNull?.let { JsonPrimitive("content:${ContentRef.parse(it).sha256}") }
                    ?: JsonNull
        }
        semantics(type, source, data)
        return JsonObject(data)
    }

    private fun semantics(
        type: SessionExportType,
        source: JsonObject,
        data: MutableMap<String, JsonElement>,
    ) {
        when (type) {
            SessionExportType.MODEL_CALL -> {
                model(source, data)
            }

            SessionExportType.TOOL_CALL -> {
                data["modelCallId"] = JsonNull
                data["modelAssociation"] = JsonPrimitive("not_persisted")
                data["callOrder"] = JsonPrimitive("not_persisted")
            }

            SessionExportType.TURN -> {
                data["terminationReasonSource"] = JsonPrimitive("persisted_state_and_errorCode_only")
                data["usage"] = unknown("not_persisted_on_turn")
            }

            SessionExportType.USAGE -> {
                data["source"] =
                    JsonPrimitive(
                        if (source.containsKey("correlationId")) "persisted_diagnostic" else "persisted_goal_aggregate",
                    )
                data["aggregation"] = JsonPrimitive("overlapping_levels_do_not_sum")
            }

            SessionExportType.GOAL_RUN -> {
                data["usageSource"] =
                    JsonPrimitive("persisted_run_aggregate_do_not_sum_with_model_usage")
            }

            SessionExportType.GOAL_BINDING -> {
                data["derived"] = JsonPrimitive(true)
            }

            SessionExportType.ARTIFACT -> {
                data["availability"] = JsonPrimitive("reference_only")
                data["verification"] = JsonPrimitive("not_read")
            }

            else -> {
                Unit
            }
        }
    }

    fun text(
        original: String,
        structured: Boolean = false,
    ): JsonObject {
        val bytes = original.toByteArray(Charsets.UTF_8)
        val sourceHash = FileContentStore.sha256Hex(bytes)
        // Raw field bodies exceeding the inline limit are explicitly omitted, not externally retrievable content.
        val clean =
            if (bytes.size <=
                SessionExportFormat.INLINE_BYTES
            ) {
                sanitizer.sanitize(original, structured)
            } else {
                null
            }
        val exported = clean?.toByteArray(Charsets.UTF_8)
        val inline = exported != null && exported.size <= SessionExportFormat.INLINE_BYTES
        return buildJsonObject {
            put("sourceBytes", bytes.size)
            put("sourceSha256", sourceHash)
            put("hashAlgorithm", "SHA-256")
            put(
                "availability",
                if (!inline) {
                    "omitted_limit"
                } else if (clean != original) {
                    "redacted"
                } else {
                    "inline"
                },
            )
            if (inline) {
                put("text", clean)
                put("exportedBytes", exported.size)
                put("exportedSha256", FileContentStore.sha256Hex(exported))
            } else {
                put("reason", "field_body_exceeds_inline_limit")
            }
        }
    }

    private fun model(
        source: JsonObject,
        data: MutableMap<String, JsonElement>,
    ) {
        data.remove("providerSnapshot")
        val provider = source["providerSnapshot"]?.jsonPrimitive?.contentOrNull?.let(SessionExportJson::objectOrNull)
        data["provider"] = provider?.let {
            buildJsonObject {
                // Endpoint and capabilities are configuration, not historical model identity.
                listOf("displayName", "model").forEach { key ->
                    val value = it[key] as? JsonPrimitive
                    put(key, value?.contentOrNull?.let { name -> text(name) } ?: JsonNull)
                }
            }
        } ?: unknown("provider_snapshot_unavailable")
        data["finishReason"] = unknown("not_persisted")
        data["timestamps"] = unknown("not_persisted")
        val usage = source["usage"]?.jsonPrimitive?.contentOrNull?.let(SessionExportJson::objectOrNull)
        data["usage"] =
            buildJsonObject {
                put("inputTokens", (usage?.get("inputTokens") as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 })
                put("outputTokens", (usage?.get("outputTokens") as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 })
                put("totalTokens", JsonNull)
                put("source", if (usage == null) "unavailable" else "persisted_usage_report_or_estimate_unknown")
            }
    }

    companion object {
        private val BODY_FIELDS =
            setOf("title", "argsJson", "summary", "limitsJson", "budgets", "redactedPayload", "promptSections")
        private val STRUCTURED_FIELDS = setOf("argsJson", "limitsJson", "budgets", "redactedPayload", "promptSections")

        fun unknown(reason: String): JsonObject =
            buildJsonObject {
                put("value", JsonNull)
                put("availability", "unknown")
                put("reason", reason)
            }
    }
}

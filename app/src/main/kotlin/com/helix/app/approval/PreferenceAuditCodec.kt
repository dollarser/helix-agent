package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.ToolApprovalReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Explicit versioned wire codec; absent legacy fields do not invent preference evidence. */
internal object PreferenceAuditCodec {
    fun encode(value: PreferenceAuditPayload): JsonObject =
        buildJsonObject {
            put("version", value.version)
            put("effective", value.effective)
            put("source", value.source.name)
            put("sourceHash", value.sourceHash)
            put("contractHash", value.contractHash)
            put("policy", value.policy)
            put("policyDenial", value.policyDenial?.let(::JsonPrimitive) ?: JsonNull)
            put("resolution", value.resolution)
            put("reason", value.reason.name)
            put(
                "rules",
                JsonArray(
                    value.rules.map { rule ->
                        buildJsonObject {
                            put("id", rule.id?.let(::JsonPrimitive) ?: JsonNull)
                            put("revision", rule.revision?.let(::JsonPrimitive) ?: JsonNull)
                            put("scope", rule.scope.name)
                            put("scopeHash", rule.scopeHash?.let(::JsonPrimitive) ?: JsonNull)
                            put("storedPreference", rule.storedPreference.name)
                            put("contractValid", rule.contractValid)
                        }
                    },
                ),
            )
        }

    fun decode(value: String): PreferenceAuditPayload? =
        runCatching {
            val obj = Json.parseToJsonElement(value).jsonObject
            require(obj.getValue("version").jsonPrimitive.int == 1)
            PreferenceAuditPayload(
                effective = obj.text("effective"),
                source = ToolApprovalReason.valueOf(obj.text("source")),
                sourceHash = obj.text("sourceHash"),
                contractHash = obj.text("contractHash"),
                policy = obj.text("policy"),
                policyDenial = obj.nullableText("policyDenial"),
                resolution = obj.text("resolution"),
                reason = ToolApprovalReason.valueOf(obj.text("reason")),
                rules = obj.getValue("rules").jsonArray.map { decodeRule(it.jsonObject) },
            )
        }.getOrNull()

    private fun decodeRule(obj: JsonObject) =
        PreferenceAuditRule(
            id = obj.nullableText("id"),
            revision = obj.getValue("revision").jsonPrimitive.longOrNull,
            scope = ToolApprovalPreferenceScope.valueOf(obj.text("scope")),
            scopeHash = obj.nullableText("scopeHash"),
            storedPreference = ToolApprovalPreference.valueOf(obj.text("storedPreference")),
            contractValid = obj.getValue("contractValid").jsonPrimitive.boolean,
        )

    private fun JsonObject.text(key: String): String =
        getValue(key).jsonPrimitive.let {
            require(it.isString)
            it.content
        }

    private fun JsonObject.nullableText(key: String): String? = if (getValue(key) == JsonNull) null else text(key)
}

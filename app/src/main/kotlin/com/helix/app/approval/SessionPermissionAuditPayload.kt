package com.helix.app.approval

import com.helix.tools.framework.SessionPermissionDecisionAudit
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
import kotlinx.serialization.json.put

/**
 * The audit-page view of one session-permission decision (HXA-209 B3, ADR section 5): mode,
 * config version, the classified effects (determined + undetermined), the rm-rule hit, the
 * outcome, the denial code and the precise reasons. Versioned allowlist — enum names and
 * reason tokens only; no policy prose, arguments, scope paths or bodies ever enter the row.
 */
data class SessionPermissionAuditPayload(
    val mode: String,
    val configVersion: Int,
    val effects: List<String>,
    val undeterminedEffects: List<String>,
    val rmCommandHit: Boolean,
    val outcome: String,
    val denyCode: String? = null,
    val reasons: List<String>,
) {
    companion object {
        fun from(decision: SessionPermissionDecisionAudit) =
            SessionPermissionAuditPayload(
                mode = decision.mode,
                configVersion = decision.configVersion,
                effects = decision.effects,
                undeterminedEffects = decision.undeterminedEffects,
                rmCommandHit = decision.rmCommandHit,
                outcome = decision.outcome,
                denyCode = decision.denyCode,
                reasons = decision.reasons,
            )

        fun encode(decision: SessionPermissionDecisionAudit): JsonObject = encodeValue(from(decision))

        /** A violation of the allowlist shape decodes to null (fail closed: the row is hidden). */
        fun decode(value: String): SessionPermissionAuditPayload? = decodeValue(value)

        private const val VERSION = 1

        private fun encodeValue(value: SessionPermissionAuditPayload): JsonObject =
            buildJsonObject {
                put("version", VERSION)
                put("mode", value.mode)
                put("configVersion", value.configVersion)
                put("effects", JsonArray(value.effects.map(::JsonPrimitive)))
                put("undeterminedEffects", JsonArray(value.undeterminedEffects.map(::JsonPrimitive)))
                put("rmCommandHit", value.rmCommandHit)
                put("outcome", value.outcome)
                put("denyCode", value.denyCode?.let(::JsonPrimitive) ?: JsonNull)
                put("reasons", JsonArray(value.reasons.map(::JsonPrimitive)))
            }

        private fun decodeValue(value: String): SessionPermissionAuditPayload? =
            runCatching {
                val obj = Json.parseToJsonElement(value).jsonObject
                require(obj.getValue("version").jsonPrimitive.int == VERSION)
                SessionPermissionAuditPayload(
                    mode = obj.text("mode"),
                    configVersion = obj.getValue("configVersion").jsonPrimitive.int,
                    effects = obj.stringList("effects"),
                    undeterminedEffects = obj.stringList("undeterminedEffects"),
                    rmCommandHit = obj.getValue("rmCommandHit").jsonPrimitive.boolean,
                    outcome = obj.text("outcome"),
                    denyCode = obj.nullableText("denyCode"),
                    reasons = obj.stringList("reasons"),
                )
            }.getOrNull()

        private fun JsonObject.text(key: String): String =
            getValue(key).jsonPrimitive.let {
                require(it.isString)
                it.content
            }

        private fun JsonObject.nullableText(key: String): String? = if (getValue(key) == JsonNull) null else text(key)

        private fun JsonObject.stringList(key: String): List<String> =
            getValue(key).jsonArray.map { element ->
                element.jsonPrimitive.let {
                    require(it.isString)
                    it.content
                }
            }
    }
}

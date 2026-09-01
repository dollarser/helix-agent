package com.helix.app.approval

import com.helix.core.model.RiskLevel
import com.helix.core.storage.repository.AuditEventRepository
import com.helix.tools.framework.AuditSink
import com.helix.tools.framework.DecisionSource
import com.helix.tools.framework.DispatchAuditEvent
import com.helix.tools.framework.DispatchOutcomeCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The production [AuditSink] (roadmap HXA-035/036): maps the dispatcher's single per-dispatch
 * event onto the `audit_events` table (architecture doc section 9.1).
 *
 * Redaction is structural: the [DispatchAuditEvent] itself carries NO argument or output
 * body (only hashes and bounded metadata — HXA-035), and [payload] is an ALLOWLIST builder
 * — the only keys that ever enter `redactedPayload` are the ones listed in
 * [PAYLOAD_KEYS]. Adding a key here is a deliberate, reviewable decision; there is no
 * pass-through path (doc 07 section 3: central allowlist, no bodies in audit).
 *
 * Fail closed (HXA-035 contract): a storage failure propagates OUT of
 * `ToolDispatcher.dispatch` — a dispatch that cannot be audited is not a successful
 * dispatch (AGENTS: no catch-all success).
 */
class StorageAuditSink(
    private val auditEvents: AuditEventRepository,
    private val idGenerator: () -> String,
) : AuditSink {
    override fun record(event: DispatchAuditEvent) {
        auditEvents.append(
            id = idGenerator(),
            correlationId = event.correlationId,
            type = TYPE,
            actor = ACTOR,
            redactedPayload = payload(event),
            timestamp = event.startedAt,
        )
    }

    companion object {
        /** The `audit_events.type` value for tool-dispatch audit rows. */
        const val TYPE = "tool_dispatch"

        const val ACTOR = "dispatcher"

        /** The EXACT key set of a tool-dispatch redacted payload (allowlist). */
        val PAYLOAD_KEYS: Set<String> =
            setOf(
                "turnId",
                "sessionId",
                "toolName",
                "toolVersion",
                "code",
                "decisionSource",
                "risk",
                "bindingHash",
                "actionFingerprint",
                "outputHash",
                "outputTruncated",
                "startedAt",
                "policyDecidedAt",
                "approvalAcquiredAt",
                "executionStartedAt",
                "finishedAt",
            )

        /**
         * Builds the redacted payload JSON — allowlist only, no bodies. The shape is
         * STABLE: every [PAYLOAD_KEYS] key is always present (null when the fact was
         * never produced), so a payload that violates the key set is detectable as
         * malformed (fail closed in [parseRow]).
         */
        fun payload(event: DispatchAuditEvent): String =
            buildJsonObject {
                put("turnId", event.turnId)
                put("sessionId", event.sessionId)
                put("toolName", event.toolName)
                put("toolVersion", event.toolVersion)
                put("code", event.code.name)
                put("decisionSource", event.decisionSource.name)
                putNullable("risk", event.riskLevel?.name)
                putNullable("bindingHash", event.bindingHash)
                putNullable("actionFingerprint", event.actionFingerprint)
                putNullable("outputHash", event.outputHash)
                put("outputTruncated", event.outputTruncated)
                put("startedAt", event.startedAt)
                putNullableLong("policyDecidedAt", event.policyDecidedAt)
                putNullableLong("approvalAcquiredAt", event.approvalAcquiredAt)
                putNullableLong("executionStartedAt", event.executionStartedAt)
                put("finishedAt", event.finishedAt)
            }.toString()

        /**
         * Parses a stored row back into a typed record for the audit page. Returns null
         * (fail closed: the row is hidden, never shown raw) when the row is not a
         * tool-dispatch event or its payload violates the allowlist shape.
         */
        fun parseRow(
            id: String,
            correlationId: String,
            type: String,
            actor: String,
            redactedPayload: String,
            timestamp: Long,
        ): DispatchAuditRecord? =
            if (type != TYPE) {
                null
            } else {
                parsePayloadObject(redactedPayload)?.let { obj ->
                    DispatchAuditRecord(
                        id = id,
                        correlationId = correlationId,
                        actor = actor,
                        turnId = obj.optString("turnId"),
                        sessionId = optBlankToNull(obj, "sessionId"),
                        toolName = obj.optString("toolName"),
                        toolVersion = obj.optString("toolVersion"),
                        code = codeOrNull(obj.optString("code")),
                        decisionSource = sourceOrNull(obj.optString("decisionSource")),
                        risk = riskOrNull(obj.optString("risk")),
                        bindingHash = obj.optString("bindingHash"),
                        actionFingerprint = obj.optString("actionFingerprint"),
                        outputHash = obj.optString("outputHash"),
                        startedAt = obj.optLong("startedAt") ?: timestamp,
                        finishedAt = obj.optLong("finishedAt") ?: timestamp,
                    )
                }
            }

        /** A payload stored as a JSON object, or null for any malformed text (the row is
         * hidden, never rendered raw — the parse failure is the handling). */
        private fun parsePayloadObject(redactedPayload: String): JsonObject? {
            val element =
                runCatching {
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(redactedPayload)
                }.getOrNull()
            return element?.asJsonObject()
        }

        // Malformed enum values parse to null (the record is then incomplete and hidden) —
        // never rendered as a raw string.
        private fun codeOrNull(value: String?): DispatchOutcomeCode? =
            value?.let { runCatching { DispatchOutcomeCode.valueOf(it) }.getOrNull() }

        private fun sourceOrNull(value: String?): DecisionSource? =
            value?.let { runCatching { DecisionSource.valueOf(it) }.getOrNull() }

        private fun riskOrNull(value: String?): RiskLevel? =
            value?.let { runCatching { RiskLevel.valueOf(it) }.getOrNull() }

        private fun optBlankToNull(
            obj: JsonObject,
            key: String,
        ): String? = obj.optString(key)?.takeIf { it.isNotBlank() }

        private fun JsonObjectBuilder.putNullable(
            key: String,
            value: String?,
        ) {
            put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
        }

        private fun JsonObjectBuilder.putNullableLong(
            key: String,
            value: Long?,
        ) {
            put(key, value?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }
}

/**
 * A parsed, typed tool-dispatch audit record for the audit log page (roadmap HXA-036).
 * Only redacted fields exist here — the type has NO slot for argument or output content,
 * so the page cannot render what the allowlist never stored.
 */
data class DispatchAuditRecord(
    val id: String,
    val correlationId: String,
    val actor: String,
    val turnId: String?,
    val sessionId: String?,
    val toolName: String?,
    val toolVersion: String?,
    val code: DispatchOutcomeCode?,
    val decisionSource: DecisionSource?,
    val risk: RiskLevel?,
    val bindingHash: String? = null,
    val actionFingerprint: String? = null,
    val outputHash: String? = null,
    val startedAt: Long,
    val finishedAt: Long,
) {
    /** True when every mandatory display fact parsed (the page hides rows that fail). */
    val complete: Boolean
        get() =
            turnId != null &&
                sessionId != null &&
                toolName != null &&
                code != null &&
                decisionSource != null
}

/** The audit page filter (roadmap HXA-036: 会话、工具、风险和日期). */
data class AuditLogFilter(
    val sessionId: String? = null,
    val toolName: String? = null,
    val risk: RiskLevel? = null,
    /** Inclusive start day, ISO `yyyy-MM-dd` (system zone). */
    val fromDay: String? = null,
    /** Inclusive end day, ISO `yyyy-MM-dd` (system zone). */
    val toDay: String? = null,
) {
    val isEmpty: Boolean
        get() =
            sessionId == null &&
                toolName == null &&
                risk == null &&
                fromDay == null &&
                toDay == null

    companion object {
        /** Builds a filter from the audit page's raw UI state (blank date inputs mean "no bound"). */
        fun fromUi(
            sessionId: String?,
            toolName: String?,
            risk: RiskLevel?,
            fromDayRaw: String,
            toDayRaw: String,
        ) = AuditLogFilter(
            sessionId = sessionId,
            toolName = toolName,
            risk = risk,
            fromDay = fromDayRaw.takeIf { it.isNotBlank() },
            toDay = toDayRaw.takeIf { it.isNotBlank() },
        )
    }
}

private fun JsonElement.asJsonObject(): JsonObject? = this as? JsonObject

private fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.optLong(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

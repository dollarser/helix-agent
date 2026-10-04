package com.helix.app.chat

import com.helix.core.agent.ToolLoopProgress
import com.helix.core.model.Capability
import com.helix.core.model.ToolOperationClass
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.entity.ToolResultEntity
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.JobObservationEvidence
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Uses persisted canonical business arguments and complete content fingerprints, never UI summaries alone. */
internal class DurableToolLoopProgress(
    private val storage: HelixStorage,
    private val registry: ToolRegistry,
) {
    fun evaluate(turnId: String): ToolLoopProgress.Decision {
        val reset =
            storage.auditEvents
                .listByCorrelation(turnId)
                .lastOrNull { it.type == RESET }
                ?.redactedPayload
        val calls = storage.toolCalls.recentByTurn(turnId, ToolLoopProgress.WINDOW)
        val start = if (reset == null) 0 else calls.indexOfLast { it.id == reset } + 1
        return fromHistory(calls.drop(start), storage.toolResults::byToolCall, ::trustedObservation) { name, version ->
            registry.all().find { it.name.value == name && it.version.value.toString() == version }
        }
    }

    private fun trustedObservation(call: ToolCallEntity): JsonObject? {
        val events = storage.auditEvents.recentByCorrelation(call.callId, "tool_dispatch", 1)
        val event = events.singleOrNull()?.takeIf { it.actor == "dispatcher" } ?: return null
        return try {
            val payload = Json.parseToJsonElement(event.redactedPayload).jsonObject
            val trusted =
                payload["jobObservation"] == JsonPrimitive(true) &&
                    payload["turnId"] == JsonPrimitive(call.turnId) &&
                    payload["toolName"] == JsonPrimitive(call.name) &&
                    payload["toolVersion"] == JsonPrimitive(call.version) &&
                    payload["code"] == JsonPrimitive("SUCCESS")
            if (trusted) payload["executionDetail"] as? JsonObject else null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun reset(
        turnId: String,
        id: String,
        now: Long,
    ) {
        val last = storage.toolCalls.recentByTurn(turnId, 1).lastOrNull() ?: return
        storage.auditEvents.append(id, turnId, RESET, "agent", last.id, now)
    }

    companion object {
        private const val RESET = "loop.user_steering"

        private fun observationFact(payload: JsonObject): ToolLoopProgress.Fact? =
            try {
                val fingerprint = JobObservationEvidence.fingerprint(payload)
                val awaiting = JobObservationEvidence.waiting(payload)
                ToolLoopProgress.Fact(fingerprint, awaiting)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }

        fun fromHistory(
            calls: List<ToolCallEntity>,
            resultFor: (String) -> ToolResultEntity?,
            observationFor: (ToolCallEntity) -> JsonObject? = { null },
            descriptorFor: (String, String) -> ToolDescriptor?,
        ): ToolLoopProgress.Decision {
            val observations =
                calls.takeLast(ToolLoopProgress.WINDOW).map { call ->
                    val descriptor = descriptorFor(call.name, call.version)
                    val result = resultFor(call.id)
                    val observation = observationFor(call)
                    if (call.state == "COMPLETED" && result?.verified == true && observation != null) {
                        val fact = observationFact(observation)
                        if (fact != null) return@map fact
                    }
                    val denied = call.state in setOf("DENIED", "FAILED") && result?.status in setOf("DENIED", "FAILED")
                    val stableRead =
                        descriptor?.operationClass == ToolOperationClass.READ_ONLY &&
                            descriptor.idempotency == Idempotency.IDEMPOTENT &&
                            descriptor.requiredCapabilities
                                .intersect(
                                    setOf(Capability.ACCESSIBILITY_AUTOMATION, Capability.MOBILE_USE),
                                ).isEmpty() &&
                            call.state == "COMPLETED" && result?.verified == true
                    ToolLoopProgress.Fact(
                        if (result == null || (!denied && !stableRead)) {
                            null
                        } else {
                            listOf(
                                call.name,
                                call.version,
                                call.argsHash,
                                result.status,
                                result.contentRef,
                                result.summary,
                            ).let { values -> JsonArray(values.map { JsonPrimitive(it) }).toString() }
                        },
                    )
                }
            return ToolLoopProgress.evaluateFacts(observations)
        }
    }
}

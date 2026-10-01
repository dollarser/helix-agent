package com.helix.app.chat

import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.ContextCompiler
import com.helix.core.agent.JobContextCandidate
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.DataOrigin
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.JobObservationEvidence
import com.helix.tools.framework.ToolDispatchRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Reads durable metadata only. Uses the same visible binding and live policy as dispatch, never a Runtime query. */
internal class JobObservationContext(
    private val storage: HelixStorage,
    private val pipeline: ToolPipeline,
) {
    fun include(
        request: TurnContextRequest,
        sessionId: String,
        turnId: String,
        control: RunControlConfig,
        profile: SafetyProfile,
    ): TurnContextRequest {
        val rows = storage.auditEvents.recentByCorrelation(sessionId, JobObservationJournal.TYPE, 64)
        val candidates =
            rows.mapNotNull { row ->
                if (row.actor != "platform") return@mapNotNull null
                val facts =
                    try {
                        Json.parseToJsonElement(row.redactedPayload).jsonObject
                    } catch (_: IllegalArgumentException) {
                        return@mapNotNull null
                    }
                candidate(row.id, facts) { evidence ->
                    request.tools.any { schema ->
                        val bound = schema.bindingRef?.let(pipeline.registry::resolveBinding) ?: return@any false
                        if (pipeline.disabledToolFilter?.invoke(sessionId, bound.descriptor) == false) return@any false
                        val args =
                            buildJsonObject {
                                put("originalCallId", evidence.getValue("handle"))
                                put("handles", JsonArray(listOf(evidence.getValue("handle"))))
                            }
                        pipeline.dispatcher.mayReadJobObservation(
                            ToolDispatchRequest(
                                toolCallId = row.id,
                                turnId = turnId,
                                sessionId = sessionId,
                                toolName = bound.ref.name,
                                toolVersion = bound.ref.version,
                                args = args,
                                mode = control.mode,
                                chatToolsEnabled = control.chatToolsEnabled,
                                profile = profile,
                                executionTarget = bound.descriptor.executionTarget,
                                dataOrigin = DataOrigin.WORKSPACE,
                                scope = null,
                                uiToken = "context-observation",
                                bindingRef = bound.ref,
                            ),
                            evidence,
                        )
                    }
                }
            }
        val remaining = (control.budgets.maxInputTokens - request.inputTokens()).coerceAtLeast(0)
        val maxBytes = remaining.coerceAtMost(8192).toInt()
        return ContextCompiler.withJobObservations(request, candidates, System.currentTimeMillis(), maxBytes)
    }

    companion object {
        /** Invalid optional evidence is omitted; I/O failures occur outside this parser and remain failures. */
        fun candidate(
            id: String,
            facts: JsonObject,
            allowed: (JsonObject) -> Boolean,
        ): JobContextCandidate? =
            try {
                val value = JobObservationEvidence.decode(facts)
                val clean = JobObservationEvidence.encode(value)
                if (!allowed(clean)) {
                    null
                } else {
                    JobContextCandidate(
                        JsonArray(
                            listOf(
                                value.binding.sessionId,
                                value.binding.providerRef,
                                value.binding.executionId,
                                value.binding.generation,
                            ).map(::JsonPrimitive),
                        ).toString(),
                        value.revision,
                        value.observedAtMillis,
                        "audit:$id",
                        value.terminal,
                        clean,
                    )
                }
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
    }
}

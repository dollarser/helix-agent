package com.helix.app.runtimebridge

import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The outer script cannot turn an unsettled/UNKNOWN child into a settled model batch. */
internal fun reviewUnresolvedScript(
    result: ToolExecutorResult,
    unresolved: Set<String>,
): ToolExecutorResult {
    if (unresolved.isEmpty()) return result
    val ids = JsonArray(unresolved.sorted().take(32).map(::JsonPrimitive))
    val originalAudit =
        when (result) {
            is ToolExecutorResult.Completed -> result.auditDetail
            is ToolExecutorResult.Failed -> result.auditDetail
            is ToolExecutorResult.TimedOutWithEffectTruth -> result.auditDetail
            is ToolExecutorResult.CancelledWithEffectTruth -> result.auditDetail
            ToolExecutorResult.Cancelled, ToolExecutorResult.TimedOut -> null
        }
    val detail =
        buildJsonObject {
            put("status", "SCRIPT_CHILD_REQUIRES_REVIEW")
            put("childCallIds", ids)
            put("unresolvedChildCount", unresolved.size)
            put("instruction", "Inspect original durable child results. Do not replay this script or its actions.")
            when (result) {
                is ToolExecutorResult.Completed -> put("scriptOutput", result.output)
                is ToolExecutorResult.Failed -> put("scriptFailure", result.detail)
                is ToolExecutorResult.TimedOutWithEffectTruth -> put("scriptFailure", result.detail)
                is ToolExecutorResult.CancelledWithEffectTruth -> put("scriptFailure", result.detail)
                ToolExecutorResult.Cancelled -> put("scriptOutcome", "CANCELLED")
                ToolExecutorResult.TimedOut -> put("scriptOutcome", "TIMEOUT")
            }
        }
    return ToolExecutorResult.Failed(
        detail.toString(),
        sideEffectFree = false,
        requiresReview = true,
        auditDetail =
            JsonObject(
                originalAudit.orEmpty() +
                    mapOf(
                        "unresolvedChildCallIds" to ids,
                        "unresolvedChildCount" to JsonPrimitive(unresolved.size),
                    ),
            ),
    )
}

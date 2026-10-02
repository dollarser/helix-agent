package com.helix.tools.automation

import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Shared effect truth for semantic and physical operations; never infer rollback or replay. */
internal fun AutomationActionResult.toToolOutcome(): ToolExecutorResult =
    if (status == AutomationActionStatus.SUCCEEDED) {
        ToolExecutorResult.Completed(buildJsonObject { put("status", JsonPrimitive(status.name)) })
    } else {
        val uncertain =
            status in
                setOf(
                    AutomationActionStatus.ACTION_FAILED,
                    AutomationActionStatus.ACTION_OUTCOME_UNKNOWN,
                )
        ToolExecutorResult.Failed(status.name, sideEffectFree = !uncertain, requiresReview = uncertain)
    }

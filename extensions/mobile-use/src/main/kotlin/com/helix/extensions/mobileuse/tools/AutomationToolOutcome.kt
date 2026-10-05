package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Shared effect truth for semantic and physical operations; never infer rollback or replay. */
internal fun AutomationActionResult.toToolOutcome(): ToolExecutorResult =
    if (status == AutomationActionStatus.SUCCEEDED) {
        ToolExecutorResult.Completed(
            buildJsonObject {
                put("status", JsonPrimitive(status.name))
                put("observationRequired", JsonPrimitive(true))
                put("nextObservation", JsonPrimitive("ui.snapshot or ui.find; ui.device for coordinate actions"))
                put(
                    "actionHint",
                    JsonPrimitive(
                        "Action dispatched. All previous node tokens and coordinate frames are now invalid. " +
                            "Before any further action, observe again using nextObservation. " +
                            "Verify the actual result; dispatch success alone does not finish the task.",
                    ),
                )
            },
        )
    } else {
        val uncertain =
            status in
                setOf(
                    AutomationActionStatus.ACTION_FAILED,
                    AutomationActionStatus.ACTION_OUTCOME_UNKNOWN,
                )
        ToolExecutorResult.Failed(status.name, sideEffectFree = !uncertain, requiresReview = uncertain)
    }

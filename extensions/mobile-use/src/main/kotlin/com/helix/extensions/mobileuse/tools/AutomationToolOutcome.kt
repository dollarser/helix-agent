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
                            "Verify the actual result; dispatch success does not prove " +
                            "touch acceptance or task completion. If there is no observed " +
                            "progress, reassess before retrying. Do not automatically " +
                            "replay an uncertain action on another backend.",
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
        val message =
            if (status == AutomationActionStatus.ACTION_NOT_SUPPORTED) {
                "${status.name}: This action was rejected as unsupported. Observe again with ui.snapshot or ui.find; " +
                    "choose a target supporting the requested action (a scrollable container for ui.scroll). " +
                    "Do not repeat the same call or guess coordinates."
            } else if (status in setOf(AutomationActionStatus.STALE_TOKEN, AutomationActionStatus.TARGET_CHANGED)) {
                "${status.name}: The observed target changed before dispatch. Obtain a fresh observation. " +
                    "If tokens repeatedly expire on a changing page, use ui.click_match with an exact, " +
                    "unique observed selector to find and click atomically. Never reuse the stale token " +
                    "or guess coordinates."
            } else {
                status.name
            }
        ToolExecutorResult.Failed(message, sideEffectFree = !uncertain, requiresReview = uncertain)
    }

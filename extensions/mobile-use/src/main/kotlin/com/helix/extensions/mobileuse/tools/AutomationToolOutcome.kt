package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Shared effect truth for semantic and physical operations; never infer rollback or replay. */
internal fun AutomationActionResult.toToolOutcome(coordinateAction: Boolean = false): ToolExecutorResult =
    if (status == AutomationActionStatus.SUCCEEDED) {
        ToolExecutorResult.Completed(dispatchedActionPayload())
    } else {
        val uncertain =
            status in
                setOf(
                    AutomationActionStatus.ACTION_FAILED,
                    AutomationActionStatus.ACTION_OUTCOME_UNKNOWN,
                )
        val gestureHint = gestureFailureHint(status)
        val message =
            if (needsServiceRecovery(status)) {
                "${status.name}: ${missingTargetHint(status.name)}"
            } else if (status == AutomationActionStatus.SENSITIVE_UI) {
                "${status.name}: Helix refused this sensitive target before dispatch. No action was executed. " +
                    "Screenshots do not grant permission or remove system restrictions."
            } else if (status == AutomationActionStatus.MAIN_DISPLAY_IN_USE) {
                "MAIN_DISPLAY_IN_USE: No launch dispatched. This app is visible on the main screen. " +
                    "The user can allow moving it in Mobile Use display settings, or keep using it there. " +
                    "Both displays share the same app data; never switch displays silently."
            } else if (status == AutomationActionStatus.BACKGROUND_DISPLAY_STALE) {
                "BACKGROUND_DISPLAY_STALE: No input dispatched. The owned display was replaced or released. " +
                    "Observe the selected display again; never reuse its old tokens or replay an earlier effect."
            } else if (status == AutomationActionStatus.BACKGROUND_DISPLAY_UNAVAILABLE) {
                "BACKGROUND_DISPLAY_UNAVAILABLE: No input dispatched. Background mode needs Android 12+, " +
                    "an authorized Root, computer ADB or Shizuku connection and the original active task. Check " +
                    "that connection and task; " +
                    "do not silently use the main screen."
            } else if (status == AutomationActionStatus.TOKEN_UNKNOWN) {
                "TOKEN_UNKNOWN: This token is not recognized in the active observation; no action was dispatched. " +
                    "It may belong to an older observation or have been copied incorrectly; this code alone " +
                    "does not prove time-based expiry. Obtain ui.find/ui.snapshot and copy the exact fresh token, " +
                    "then act without another observation. For a known unique click selector use ui.click_match. " +
                    "For scrolling find scrollable=true. Never reconstruct a token."
            } else if (status == AutomationActionStatus.ACTION_NOT_SUPPORTED) {
                unsupportedActionHint()
            } else if (gestureHint != null) {
                gestureHint
            } else if (status == AutomationActionStatus.STALE_TOKEN && coordinateAction) {
                staleCoordinateFrameHint()
            } else if (status == AutomationActionStatus.STALE_TOKEN) {
                "STALE_TOKEN: This observation is no longer actionable; no action was dispatched. " +
                    "A later observation, attempted action or changed target can invalidate it. " +
                    "Re-observe with ui.find/ui.snapshot and decide whether the action is still needed. " +
                    "Use only the new token without an intervening observation, or ui.click_match " +
                    "for a unique selector. Never reuse the stale token."
            } else if (status == AutomationActionStatus.TARGET_CHANGED) {
                "TARGET_CHANGED: The target changed before dispatch; no input was dispatched. " +
                    "An ad or page transition may already have completed. Observe with ui.snapshot or ui.device and " +
                    "decide whether the original action is still needed. Never replay old tokens or coordinates, " +
                    "and do not treat this refusal as proof that an earlier action failed."
            } else {
                status.name
            }
        ToolExecutorResult.Failed(message, sideEffectFree = !uncertain, requiresReview = uncertain)
    }

/** A completed input operation is not evidence that the target accepted it or the task succeeded. */
internal fun automationActionOutputSchema(includeBackend: Boolean = false): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject {
                put("status", actionLiteral("DISPATCHED"))
                put("effectStatus", actionLiteral("UNVERIFIED"))
                put(
                    "observationRequired",
                    buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("enum", JsonArray(listOf(JsonPrimitive(true))))
                    },
                )
                put("nextObservation", actionText(128))
                put("actionHint", actionText(384))
                if (includeBackend) put("backend", actionText(16))
            },
        )
        put(
            "required",
            JsonArray(
                listOf("status", "effectStatus", "observationRequired", "nextObservation", "actionHint")
                    .map(::JsonPrimitive),
            ),
        )
        put("additionalProperties", JsonPrimitive(false))
    }

private fun actionLiteral(value: String) =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("enum", JsonArray(listOf(JsonPrimitive(value))))
    }

private fun actionText(maximum: Int) =
    buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("maxLength", JsonPrimitive(maximum))
    }

private fun gestureFailureHint(status: AutomationActionStatus): String? =
    when (status) {
        AutomationActionStatus.GESTURE_OCCLUDED -> {
            "GESTURE_OCCLUDED: Another window covers the intended path; no input dispatched. " +
                "Observe the owned display's windows and choose a visible authorized path."
        }

        else -> {
            null
        }
    }

private fun dispatchedActionPayload() =
    buildJsonObject {
        put("status", JsonPrimitive("DISPATCHED"))
        put("effectStatus", JsonPrimitive("UNVERIFIED"))
        put("observationRequired", JsonPrimitive(true))
        put(
            "nextObservation",
            JsonPrimitive("ui.wait for an expected label/state; ui.snapshot or ui.find; ui.device for visual results"),
        )
        put(
            "actionHint",
            JsonPrimitive(
                "Use nextObservation to observe the result. " +
                    "Never blindly replay a completed or uncertain effect.",
            ),
        )
    }

private fun staleCoordinateFrameHint(): String =
    "STALE_TOKEN: This coordinate frame is unknown, consumed or changed; no gesture was dispatched. " +
        "Call ui.device and copy its exact opaque frame, then recheck the current target before acting. " +
        "ui.snapshot/ui.find tokens and generation numbers are not coordinate frames. " +
        "Every attempted gesture consumes its frame; never reuse it or blindly replay an earlier effect."

private fun unsupportedActionHint(): String =
    "ACTION_NOT_SUPPORTED: This action was rejected as unsupported. Observe again with ui.snapshot or ui.find; " +
        "choose a target supporting the requested action (a scrollable container for ui.scroll). " +
        "For ui.system, check ui.device.systemActions; use back to close the shade when " +
        "dismiss_notification_shade is unavailable. " +
        "For scrolling, copy one of its advertised scrollDirections exactly: these " +
        "are viewport directions, " +
        "not finger motion, and a horizontal edge may expose only right/forward or left/backward. " +
        "Do not repeat the same call or guess coordinates."

private fun needsServiceRecovery(status: AutomationActionStatus): Boolean =
    status in setOf(AutomationActionStatus.SERVICE_NOT_CONNECTED, AutomationActionStatus.PRIVILEGED_SERVICE_UNAVAILABLE)

package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Shared effect truth for semantic and physical operations; never infer rollback or replay. */
internal fun AutomationActionResult.toToolOutcome(): ToolExecutorResult =
    if (status == AutomationActionStatus.SUCCEEDED) {
        ToolExecutorResult.Completed(
            buildJsonObject {
                put("status", JsonPrimitive("DISPATCHED"))
                put("effectStatus", JsonPrimitive("UNVERIFIED"))
                put("observationRequired", JsonPrimitive(true))
                put("nextObservation", JsonPrimitive("ui.snapshot or ui.find; ui.device for coordinate actions"))
                put(
                    "actionHint",
                    JsonPrimitive(
                        "Input dispatch completed; effect is UNVERIFIED. Previous tokens/frames are invalid. " +
                            "Before another action or reporting completion, observe using nextObservation. " +
                            "An unchanged screen proves neither failure nor touch filtering. " +
                            "Never blindly retry or replay an uncertain effect on another backend.",
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
            if (status == AutomationActionStatus.SENSITIVE_UI) {
                "${status.name}: Helix refused this sensitive target before dispatch. No action was executed. " +
                    "Screenshots do not grant permission or remove system restrictions."
            } else if (status == AutomationActionStatus.TOKEN_UNKNOWN) {
                "${status.name}: This token is not in the active observation; no action was dispatched. " +
                    "Earlier actions and new observations invalidate old tokens. Read the attached post-action " +
                    "observation if available, or obtain ui.snapshot/ui.find again. For a scroll container use " +
                    "ui.find with scrollable=true. Copy the exact fresh token; never reconstruct or reuse the old one."
            } else if (status == AutomationActionStatus.ACTION_NOT_SUPPORTED) {
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

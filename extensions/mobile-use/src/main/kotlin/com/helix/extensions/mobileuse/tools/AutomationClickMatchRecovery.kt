package com.helix.extensions.mobileuse.tools

import com.helix.tools.framework.ToolExecutorResult

/** Pre-dispatch selector refusals only. Never use this helper for an uncertain input outcome. */
internal fun clickMatchRefusal(status: String): ToolExecutorResult.Failed {
    val hint =
        when (status) {
            "TARGET_NOT_FOUND" -> {
                "No click dispatched. Inspect ui.snapshot for the current page and selector. " +
                    "If the control is still loading, use a bounded timeoutMillis on ui.click_match. " +
                    "A timeout does not prove an earlier action failed; do not repeat unchanged waits."
            }

            "TARGET_AMBIGUOUS" -> {
                "No click dispatched. Inspect ui.find/ui.snapshot and narrow the selector " +
                    "with an observed viewId, exact text or other attributes; packageName pins the app. " +
                    "Do not choose the first match or guess coordinates."
            }

            "TARGET_NOT_CLICKABLE" -> {
                "No click dispatched. Inspect the latest ui.find/ui.snapshot for " +
                    "an actionable parent via clickTargetToken; scroll a fresh container for offscreen targets."
            }

            "INVALID_QUERY" -> {
                "No click dispatched. Check the selector against the tool schema and latest observation."
            }

            else -> {
                missingTargetHint(status)
            }
        }
    return ToolExecutorResult.Failed(if (hint == null) status else "$status: $hint", sideEffectFree = true)
}

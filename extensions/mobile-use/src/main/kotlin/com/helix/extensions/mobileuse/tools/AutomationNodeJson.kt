package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Compact observations and detailed query results share identical token and state semantics. */
internal object AutomationNodeJson {
    fun compact(
        node: AutomationSnapshotNode,
        nodeIndex: Map<String, AutomationSnapshotNode>,
    ): JsonObject =
        JsonObject(
            full(node, nodeIndex).filter { (key, value) ->
                key in setOf("token", "clickable", "enabled", "bounds") ||
                    key == "editableValue" || key == "isShowingHintText" ||
                    (key == "text" && node.editable && node.isShowingHintText == true) ||
                    (
                        key !in setOf("parentToken", "depth", "className") && value != JsonPrimitive("") &&
                            (value != JsonPrimitive(false) || (key == "checked" && node.checkable))
                    )
            },
        )

    fun full(
        node: AutomationSnapshotNode,
        nodeIndex: Map<String, AutomationSnapshotNode> = emptyMap(),
    ) = buildJsonObject {
        put("token", JsonPrimitive(node.token))
        put("parentToken", JsonPrimitive(node.parentToken ?: ""))
        put("clickTargetToken", JsonPrimitive(automationClickTargetToken(node, nodeIndex)))
        longClick(node, nodeIndex)?.let { put("longClick", it) }
        automationNodeContext(node, nodeIndex)?.let { put("ancestorContexts", it) }
        put("depth", JsonPrimitive(node.depth))
        put("className", JsonPrimitive(node.className ?: ""))
        // Android can expose the placeholder as text. Keep it only in hintText in model-facing output.
        // The collected node retains platform text for matching, fingerprints and audit.
        put("text", JsonPrimitive(if (node.editable && node.isShowingHintText == true) "" else node.text ?: ""))
        editableValue(node)
        put(
            "contentDescription",
            JsonPrimitive(node.contentDescription ?: ""),
        )
        put("viewId", JsonPrimitive(node.viewId ?: ""))
        put("clickable", JsonPrimitive(node.clickable))
        put("offscreen", JsonPrimitive(automationNodeOffscreen(node)))
        put("visibleToUser", JsonPrimitive(node.visibleToUser))
        put("checkable", JsonPrimitive(node.checkable))
        put("checked", JsonPrimitive(node.checked))
        put("longClickable", JsonPrimitive(node.longClickable))
        put("editable", JsonPrimitive(node.editable))
        put("scrollable", JsonPrimitive(node.scrollable))
        put(
            "scrollDirections",
            kotlinx.serialization.json.JsonArray(
                node.scrollDirections.map {
                    JsonPrimitive(it.name.lowercase(java.util.Locale.ROOT))
                },
            ),
        )
        put("enabled", JsonPrimitive(node.enabled))
        put("redacted", JsonPrimitive(node.redacted))
        put("canImeEnter", JsonPrimitive(node.canImeEnter))
        put(
            "bounds",
            buildJsonObject {
                put("left", JsonPrimitive(node.bounds.left))
                put("top", JsonPrimitive(node.bounds.top))
                put("right", JsonPrimitive(node.bounds.right))
                put("bottom", JsonPrimitive(node.bounds.bottom))
            },
        )
        put("canSetProgress", JsonPrimitive(node.canSetProgress))
        node.range?.let { range ->
            put(
                "range",
                buildJsonObject {
                    put("min", JsonPrimitive(range.min))
                    put("max", JsonPrimitive(range.max))
                    put("current", JsonPrimitive(range.current))
                },
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.editableValue(node: AutomationSnapshotNode) {
        if (!node.redacted) {
            node.hintText?.let { put("hintText", JsonPrimitive(it)) }
            node.isShowingHintText?.takeIf { node.editable || node.hintText != null }?.let { showing ->
                put("isShowingHintText", JsonPrimitive(showing))
                if (node.editable && (showing || node.text != null)) {
                    put("editableValue", JsonPrimitive(if (showing) "" else node.text))
                }
            }
        }
    }

    private fun longClick(
        node: AutomationSnapshotNode,
        index: Map<String, AutomationSnapshotNode>,
    ) = automationLongClickTargetToken(node, index).takeIf(String::isNotEmpty)?.let { token ->
        buildJsonObject {
            put("tool", JsonPrimitive(AutomationTools.LONG_CLICK))
            put("token", JsonPrimitive(token))
        }
    }
}

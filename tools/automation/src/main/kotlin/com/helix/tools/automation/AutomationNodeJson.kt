package com.helix.tools.automation

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
        put("depth", JsonPrimitive(node.depth))
        put("className", JsonPrimitive(node.className ?: ""))
        put("text", JsonPrimitive(node.text ?: ""))
        put(
            "contentDescription",
            JsonPrimitive(node.contentDescription ?: ""),
        )
        put("viewId", JsonPrimitive(node.viewId ?: ""))
        put("clickable", JsonPrimitive(node.clickable))
        put("checkable", JsonPrimitive(node.checkable))
        put("checked", JsonPrimitive(node.checked))
        put("longClickable", JsonPrimitive(node.longClickable))
        put("editable", JsonPrimitive(node.editable))
        put("scrollable", JsonPrimitive(node.scrollable))
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
}

package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Same-window overlapping labels are not proof of which target is on top. Expose their actual ancestry. */
internal fun automationNodeContext(
    node: AutomationSnapshotNode,
    index: Map<String, AutomationSnapshotNode>,
): JsonArray? {
    val label = node.label()
    val interactive = node.enabled && (node.clickable || node.longClickable)
    return if (label != null && interactive && overlapsPeer(node, index)) {
        ancestorContexts(node, index, label)
    } else {
        null
    }
}

private fun overlapsPeer(
    node: AutomationSnapshotNode,
    index: Map<String, AutomationSnapshotNode>,
): Boolean =
    index.values.any {
        it.token != node.token && it.label() == node.label() && it.visibleToUser &&
            it.bounds.left < node.bounds.right && node.bounds.left < it.bounds.right &&
            it.bounds.top < node.bounds.bottom && node.bounds.top < it.bounds.bottom
    }

private fun ancestorContexts(
    node: AutomationSnapshotNode,
    index: Map<String, AutomationSnapshotNode>,
    label: String,
): JsonArray? {
    val children = index.values.groupBy { it.parentToken }
    val contexts = mutableListOf<kotlinx.serialization.json.JsonObject>()
    val visited = mutableSetOf<String>()
    var ancestor = node.parentToken?.let(index::get)
    while (ancestor != null && contexts.size < 2 && visited.add(ancestor.token)) {
        val current = ancestor
        val labels = contextLabels(current, children, label)
        if (labels.isNotEmpty()) {
            contexts +=
                buildJsonObject {
                    put("className", JsonPrimitive(current.className.orEmpty()))
                    put("viewId", JsonPrimitive(current.viewId.orEmpty()))
                    put("labels", JsonArray(labels.map(::JsonPrimitive)))
                }
        }
        ancestor = current.parentToken?.let(index::get)
    }
    return contexts.takeIf { it.isNotEmpty() }?.let(::JsonArray)
}

private fun contextLabels(
    ancestor: AutomationSnapshotNode,
    children: Map<String?, List<AutomationSnapshotNode>>,
    excluded: String,
): List<String> {
    val pending = ArrayDeque<AutomationSnapshotNode>().apply { add(ancestor) }
    val seen = mutableSetOf<String>()
    val labels = linkedSetOf<String>()
    while (pending.isNotEmpty() && labels.size < 4) {
        val current = pending.removeFirst()
        if (!seen.add(current.token)) continue
        if (!current.redacted && !automationNodeOffscreen(current)) {
            current.label()?.takeIf { it != excluded }?.let { labels += it.take(96) }
        }
        children[current.token]?.forEach(pending::addLast)
    }
    return labels.toList()
}

private fun AutomationSnapshotNode.label(): String? =
    text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }

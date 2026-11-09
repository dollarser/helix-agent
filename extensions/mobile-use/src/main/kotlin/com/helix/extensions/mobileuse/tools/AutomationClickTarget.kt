package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode

internal fun automationClickTargetToken(
    node: AutomationSnapshotNode,
    nodeIndex: Map<String, AutomationSnapshotNode>,
): String = automationActionTargetToken(node, nodeIndex) { it.clickable }

internal fun automationLongClickTargetToken(
    node: AutomationSnapshotNode,
    nodeIndex: Map<String, AutomationSnapshotNode>,
): String = automationActionTargetToken(node, nodeIndex) { it.longClickable }

private fun automationActionTargetToken(
    node: AutomationSnapshotNode,
    nodeIndex: Map<String, AutomationSnapshotNode>,
    supports: (AutomationSnapshotNode) -> Boolean,
): String {
    var target = ""
    if (!node.redacted && !automationNodeOffscreen(node)) {
        var current: AutomationSnapshotNode? = node
        val visited = mutableSetOf<String>()
        while (current != null && target.isEmpty() && visited.add(current.token)) {
            val actionable =
                current.enabled && !current.redacted && supports(current) && !automationNodeOffscreen(current)
            if (actionable && current.token.isNotEmpty()) {
                target = current.token
            } else {
                current = current.parentToken?.let(nodeIndex::get)
            }
        }
    }
    return target
}

/** Clipped empty/inverted bounds are not actionable, even if the platform advertises clickable. */
internal fun automationNodeOffscreen(node: AutomationSnapshotNode): Boolean =
    !node.visibleToUser ||
        with(node.bounds) {
            right <= left || bottom <= top || right <= 0 || bottom <= 0
        }

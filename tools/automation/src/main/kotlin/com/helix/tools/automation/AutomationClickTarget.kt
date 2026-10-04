package com.helix.tools.automation

internal fun automationClickTargetToken(
    node: AutomationSnapshotNode,
    nodeIndex: Map<String, AutomationSnapshotNode>,
): String {
    var target = ""
    if (!node.redacted) {
        var current: AutomationSnapshotNode? = node
        val visited = mutableSetOf<String>()
        while (current != null && target.isEmpty() && visited.add(current.token)) {
            if (current.enabled && current.clickable && current.token.isNotEmpty()) {
                target = current.token
            } else {
                current = current.parentToken?.let(nodeIndex::get)
            }
        }
    }
    return target
}

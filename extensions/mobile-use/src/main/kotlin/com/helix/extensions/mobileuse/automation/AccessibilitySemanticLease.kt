package com.helix.extensions.mobileuse.automation

/** Renew only the original path and its interaction context; never relocate a similar node. */
internal class AccessibilitySemanticLease(
    snapshot: AutomationSnapshot,
    val windowEpoch: Long,
    private val diagnostic: (String) -> Unit = {},
) {
    val generation = snapshot.generation
    private val packageName = snapshot.packageName
    private val windowId = snapshot.windowId
    private val original = snapshot.indexedSemantics()

    @Suppress("ReturnCount") // Each missing identity/evidence boundary fails closed before context comparison.
    fun matches(
        snapshot: AutomationSnapshot,
        currentWindowEpoch: Long,
        token: String,
    ): Boolean {
        if (currentWindowEpoch != windowEpoch || snapshot.packageName != packageName || snapshot.windowId != windowId) {
            return refused("window_identity")
        }
        val before = original ?: return refused("original_incomplete")
        val path = before.entries.singleOrNull { it.value.token == token }?.key ?: return refused("token_missing")
        val after = snapshot.indexedSemantics() ?: return refused("fresh_incomplete")
        val matches = before.context(path) == after.context(path) && after.containsKey(path)
        if (!matches) diagnostic("context_changed")
        return matches
    }

    private fun refused(reason: String): Boolean {
        diagnostic(reason)
        return false
    }
}

/** A complete tree is still required to establish absence of an unobserved covering target. */
@Suppress("ReturnCount")
private fun AutomationSnapshot.indexedSemantics(): Map<List<Int>, AutomationSnapshotNode>? {
    if (truncated || nodes.isEmpty() || nodes.any { it.redacted }) return null
    if (nodes.map { it.token }.distinct().size != nodes.size) return null
    val paths = mutableMapOf<String, List<Int>>()
    val childCounts = mutableMapOf<String?, Int>()
    val result = linkedMapOf<List<Int>, AutomationSnapshotNode>()
    for (node in nodes) {
        val parent = node.parentToken?.let { paths[it] ?: return null }
        if (parent == null && result.isNotEmpty()) return null
        val index = childCounts.getOrDefault(node.parentToken, 0)
        childCounts[node.parentToken] = index + 1
        val path = if (parent == null) emptyList() else parent + index
        if (node.depth != path.size) return null
        paths[node.token] = path
        result[path] = node
    }
    return result
}

private fun Map<List<Int>, AutomationSnapshotNode>.context(path: List<Int>): Map<List<Int>, AutomationSnapshotNode>? {
    val target = get(path) ?: return null
    return filter { (candidate, node) ->
        candidate.take(path.size) == path || path.take(candidate.size) == candidate ||
            node.bounds.overlaps(target.bounds)
    }.mapValues { (_, node) -> node.copy(token = "", parentToken = null) }
}

private fun AutomationNodeBounds.overlaps(other: AutomationNodeBounds): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import kotlinx.serialization.json.JsonObject

/** Collection and model output have independent budgets; the full tree remains available for targeting. */
internal class AutomationSnapshotProjection(
    snapshot: AutomationSnapshot,
) {
    val nodes: List<JsonObject>
    val limited: Boolean

    init {
        val index = snapshot.nodes.associateBy { it.token }
        val relevant =
            snapshot.nodes.filter {
                it.visibleToUser && (
                    !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() ||
                        it.clickable || it.longClickable || it.editable || it.scrollable ||
                        it.checkable || it.canSetProgress || it.redacted
                )
            }
        val output = mutableListOf<JsonObject>()
        var bytes = 2
        for (node in relevant) {
            val value = AutomationNodeJson.compact(node, index)
            val size = value.toString().toByteArray(Charsets.UTF_8).size + 1
            if (output.size >= MAX_NODES || bytes + size > MAX_BYTES) break
            output += value
            bytes += size
        }
        nodes = output
        limited = output.size < relevant.size
    }

    companion object {
        const val MAX_NODES = 200
        const val MAX_BYTES = 128 * 1024
    }
}

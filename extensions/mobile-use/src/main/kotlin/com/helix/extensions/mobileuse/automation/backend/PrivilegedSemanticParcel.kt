package com.helix.extensions.mobileuse.automation.backend

import android.os.Parcel
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationNodeRange
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import java.time.Instant

/** Bounded structured nodes; no platform node objects or executable payload cross IPC. */
internal object PrivilegedSemanticParcel {
    fun write(
        parcel: Parcel,
        result: AutomationSnapshotResult?,
    ) {
        parcel.writeString(result?.status?.name)
        if (result == null) return
        val snapshot = result.snapshot
        parcel.writeInt(if (snapshot == null) 0 else 1)
        if (snapshot == null) return
        parcel.writeString(snapshot.packageName)
        parcel.writeInt(snapshot.windowId)
        parcel.writeInt(if (snapshot.truncated) 1 else 0)
        parcel.writeStringList(snapshot.truncationReasons.toList())
        parcel.writeInt(snapshot.nodes.size)
        snapshot.nodes.forEach { node ->
            listOf(node.token, node.parentToken, node.className, node.text, node.contentDescription, node.viewId)
                .forEach(parcel::writeString)
            listOf(node.depth, node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)
                .forEach(parcel::writeInt)
            listOf(
                node.clickable,
                node.longClickable,
                node.editable,
                node.scrollable,
                node.enabled,
                node.canSetProgress,
                node.redacted,
                node.canImeEnter,
                node.checkable,
                node.checked,
            ).forEach { parcel.writeInt(if (it) 1 else 0) }
            parcel.writeInt(if (node.range == null) 0 else 1)
            node.range?.let { listOf(it.min, it.max, it.current).forEach(parcel::writeFloat) }
        }
    }

    @Suppress("ReturnCount") // Distinguish absent payload, refusal and a populated snapshot.
    fun read(parcel: Parcel): AutomationSnapshotResult? {
        val status = parcel.readString()?.let(AutomationSnapshotStatus::valueOf) ?: return null
        if (parcel.readInt() == 0) return AutomationSnapshotResult(status)
        val name = requireNotNull(text(parcel, 255))
        val window = parcel.readInt()
        val truncated = parcel.readInt() == 1
        val reasons =
            requireNotNull(parcel.createStringArrayList())
                .also {
                    require(it.size <= 4)
                    require(
                        it.all { reason ->
                            reason in setOf("DEPTH_LIMIT", "NODE_LIMIT", "TEXT_LIMIT", "FIELD_LIMIT")
                        },
                    )
                }.toSet()
        val count = parcel.readInt().also { require(it in 0..200) }
        val nodes =
            List(count) {
                val strings = List(6) { text(parcel, 2000) }
                val values = List(5) { parcel.readInt() }
                val flags = List(10) { parcel.readInt() == 1 }
                val range =
                    if (parcel.readInt() == 1) {
                        AutomationNodeRange(parcel.readFloat(), parcel.readFloat(), parcel.readFloat())
                    } else {
                        null
                    }
                AutomationSnapshotNode(
                    requireNotNull(strings[0]),
                    strings[1],
                    values[0],
                    strings[2],
                    strings[3],
                    strings[4],
                    strings[5],
                    AutomationNodeBounds(values[1], values[2], values[3], values[4]),
                    flags[0],
                    flags[1],
                    flags[2],
                    flags[3],
                    flags[4],
                    range,
                    flags[5],
                    flags[6],
                    flags[7],
                    flags[8],
                    flags[9],
                )
            }
        return AutomationSnapshotResult(
            status,
            AutomationSnapshot(name, window, 0, Instant.now(), nodes, truncated, reasons),
        )
    }

    private fun text(
        parcel: Parcel,
        limit: Int,
    ): String? = parcel.readString()?.also { require(it.length <= limit) }
}

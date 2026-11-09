package com.helix.extensions.mobileuse.automation.backend

import android.os.Parcel
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationNodeRange
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotEngine
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.NodeTokenRegistry
import com.helix.extensions.mobileuse.automation.SnapshotTruncationReasons
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
                node.visibleToUser,
            ).forEach { parcel.writeInt(if (it) 1 else 0) }
            parcel.writeInt(if (node.range == null) 0 else 1)
            node.range?.let { listOf(it.min, it.max, it.current).forEach(parcel::writeFloat) }
            parcel.writeStringList(node.scrollDirections.map { it.name })
        }
    }

    @Suppress("ReturnCount") // Distinguish absent payload, refusal and a populated snapshot.
    fun read(parcel: Parcel): AutomationSnapshotResult? {
        val status = parcel.readString()?.let(AutomationSnapshotStatus::valueOf) ?: return null
        if (parcel.readInt() == 0) return AutomationSnapshotResult(status)
        val name = requireNotNull(text(parcel, 255))
        val window = parcel.readInt()
        val truncated = parcel.readInt() == 1
        val reasons = SnapshotTruncationReasons.checked(requireNotNull(parcel.createStringArrayList()))
        val count = parcel.readInt().also { require(it in 0..NodeTokenRegistry.MAX_TOKENS) }
        var characters = 0
        val nodes =
            List(count) {
                val strings = readNodeStrings(parcel)
                characters += strings.drop(2).sumOf { it?.length ?: 0 }
                require(characters <= AutomationSnapshotEngine.MAX_TOTAL_CHARACTERS)
                val values = List(5) { parcel.readInt() }
                val flags = List(11) { parcel.readInt() == 1 }
                val range =
                    if (parcel.readInt() == 1) {
                        AutomationNodeRange(parcel.readFloat(), parcel.readFloat(), parcel.readFloat())
                    } else {
                        null
                    }
                val directions = readScrollDirections(parcel)
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
                    directions,
                    flags[10],
                )
            }
        return AutomationSnapshotResult(
            status,
            AutomationSnapshot(name, window, 0, Instant.now(), nodes, truncated, reasons),
        )
    }

    private fun readNodeStrings(parcel: Parcel) =
        List(6) { index ->
            val limit = if (index < 2) 32 else AutomationSnapshotEngine.MAX_FIELD_CHARACTERS
            text(parcel, limit)
        }

    private fun readScrollDirections(parcel: Parcel) =
        requireNotNull(parcel.createStringArrayList())
            .also { require(it.size <= 6) }
            .map(com.helix.extensions.mobileuse.automation.AutomationScrollDirection::valueOf)
            .toSet()

    private fun text(
        parcel: Parcel,
        limit: Int,
    ): String? = parcel.readString()?.also { require(it.length <= limit) }
}

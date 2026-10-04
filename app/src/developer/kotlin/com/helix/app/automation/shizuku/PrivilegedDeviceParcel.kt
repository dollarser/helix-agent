package com.helix.app.automation.shizuku

import android.os.Parcel
import com.helix.tools.automation.AutomationDeviceOperation
import com.helix.tools.automation.AutomationDeviceRequest
import com.helix.tools.automation.AutomationDisplayTarget
import com.helix.tools.automation.AutomationNodeBounds
import com.helix.tools.automation.AutomationPoint
import com.helix.tools.automation.AutomationStroke

/** Closed, size-bounded IPC fields. Image bytes travel only through read-only shared memory. */
internal object PrivilegedDeviceParcel {
    fun writeTarget(
        parcel: Parcel,
        target: AutomationDisplayTarget?,
    ) {
        parcel.writeInt(if (target == null) 0 else 1)
        if (target == null) return
        parcel.writeString(target.packageName)
        val values =
            listOf(
                target.windowId,
                target.displayId,
                target.width,
                target.height,
                target.rotation,
                target.bounds.left,
                target.bounds.top,
                target.bounds.right,
                target.bounds.bottom,
            )
        values.forEach(parcel::writeInt)
        parcel.writeString(target.revision)
    }

    fun readTarget(parcel: Parcel): AutomationDisplayTarget? {
        if (parcel.readInt() == 0) return null
        val name = requireNotNull(parcel.readString())
        require(name.length in 1..255)
        val values = List(9) { parcel.readInt() }
        require(values[1] == 0 && values[2] in 1..32768 && values[3] in 1..32768 && values[4] in 0..3)
        val revision = requireNotNull(parcel.readString()).also { require(it.length <= 64) }
        val bounds = AutomationNodeBounds(values[5], values[6], values[7], values[8])
        return AutomationDisplayTarget(
            name,
            values[0],
            values[1],
            values[2],
            values[3],
            values[4],
            bounds,
            revision,
        )
    }

    fun writeRequest(
        parcel: Parcel,
        request: AutomationDeviceRequest,
    ) {
        parcel.writeInt(request.operation.ordinal)
        writeTarget(parcel, request.target)
        parcel.writeInt(if (request.wholeDisplay) 1 else 0)
        parcel.writeInt(request.strokes.size)
        request.strokes.forEach { stroke ->
            parcel.writeLong(stroke.startMillis)
            parcel.writeLong(stroke.durationMillis)
            parcel.writeInt(stroke.points.size)
            stroke.points.forEach {
                parcel.writeFloat(it.x)
                parcel.writeFloat(it.y)
            }
        }
        parcel.writeString(request.nodeFingerprint)
        parcel.writeString(request.nodeAction?.action?.name)
        request.nodeAction?.let {
            parcel.writeString(it.token)
            parcel.writeString(it.text)
            parcel.writeInt(if (it.progress == null) 0 else 1)
            it.progress?.let(parcel::writeDouble)
            parcel.writeInt(if (it.submit) 1 else 0)
        }
    }

    fun readRequest(parcel: Parcel): AutomationDeviceRequest {
        val operation = AutomationDeviceOperation.entries[parcel.readInt().also { require(it in 0..4) }]
        val target = readTarget(parcel)
        val whole = parcel.readInt() == 1
        val count = parcel.readInt().also { require(it in 0..10) }
        val strokes =
            List(count) {
                val start = parcel.readLong()
                val duration = parcel.readLong()
                val points = parcel.readInt().also { require(it in 1..512) }
                val coordinates = List(points) { AutomationPoint(parcel.readFloat(), parcel.readFloat()) }
                AutomationStroke(coordinates, start, duration)
            }
        val fingerprint = parcel.readString()?.also { require(it.length == 32) }
        val action =
            parcel.readString()?.let { name ->
                val token = requireNotNull(parcel.readString()).also { require(it.length == 32) }
                val text = parcel.readString()?.also { require(it.length <= 65_536) }
                val progress = if (parcel.readInt() == 1) parcel.readDouble().also { require(it.isFinite()) } else null
                val submit = parcel.readInt() == 1
                com.helix.tools.automation.AutomationNodeActionRequest(
                    com.helix.tools.automation.AutomationNodeAction
                        .valueOf(name),
                    token,
                    text,
                    progress,
                    submit,
                )
            }
        require((operation == AutomationDeviceOperation.OBSERVE) == (target == null))
        require((operation == AutomationDeviceOperation.GESTURE) == strokes.isNotEmpty())
        require((operation == AutomationDeviceOperation.NODE_ACTION) == (action != null))
        require((operation == AutomationDeviceOperation.NODE_ACTION) == (fingerprint != null))
        return AutomationDeviceRequest(operation, target, strokes, whole, action, fingerprint)
    }
}

package com.helix.extensions.mobileuse.automation.backend

import android.os.Parcel
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke

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
        parcel.writeString(request.launchPackage)
        parcel.writeString(request.globalAction?.name)
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

    private fun readNodeAction(parcel: Parcel): com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest? =
        parcel.readString()?.let { name ->
            val token = requireNotNull(parcel.readString()).also { require(it.length == 32) }
            val text = parcel.readString()?.also { require(it.length <= 65_536) }
            val progress = if (parcel.readInt() == 1) parcel.readDouble().also { require(it.isFinite()) } else null
            val submit = parcel.readInt() == 1
            com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest(
                com.helix.extensions.mobileuse.automation.AutomationNodeAction
                    .valueOf(name),
                token,
                text,
                progress,
                submit,
            )
        }

    fun readRequest(parcel: Parcel): AutomationDeviceRequest {
        val operation =
            AutomationDeviceOperation.entries[
                parcel.readInt().also {
                    require(it in AutomationDeviceOperation.entries.indices)
                },
            ]
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
        val launchPackage =
            parcel.readString()?.also {
                require(
                    com.helix.extensions.mobileuse.automation.AndroidPackageName
                        .isValid(it),
                )
            }
        val globalAction =
            parcel.readString()?.let {
                com.helix.extensions.mobileuse.automation.AutomationGlobalAction
                    .valueOf(it)
            }
        val fingerprint = parcel.readString()?.also { require(it.length == 32) }
        val action = readNodeAction(parcel)
        require(
            (operation in setOf(AutomationDeviceOperation.OBSERVE, AutomationDeviceOperation.LAUNCH)) ==
                (target == null),
        )
        require((operation == AutomationDeviceOperation.LAUNCH) == (launchPackage != null))
        require((operation == AutomationDeviceOperation.GLOBAL_ACTION) == (globalAction != null))
        require((operation == AutomationDeviceOperation.GESTURE) == strokes.isNotEmpty())
        require((operation == AutomationDeviceOperation.NODE_ACTION) == (action != null))
        require((operation == AutomationDeviceOperation.NODE_ACTION) == (fingerprint != null))
        return AutomationDeviceRequest(
            operation,
            target,
            strokes,
            whole,
            action,
            fingerprint,
            launchPackage,
            globalAction,
        )
    }
}

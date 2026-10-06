package com.helix.extensions.mobileuse.automation.backend

import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import com.helix.core.model.VisionLimits
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationDeviceReply
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationScreenshot
import com.helix.extensions.mobileuse.automation.hasEffect
import java.util.concurrent.atomic.AtomicBoolean

internal object PrivilegedDeviceTransactions {
    // Binder effects can be unknown; images are bounded before reading.
    @Suppress("DEPRECATION", "TooGenericExceptionCaught")
    fun execute(
        binder: IBinder,
        request: AutomationDeviceRequest,
        allowed: () -> Boolean,
    ): AutomationDeviceReply {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val active = AtomicBoolean(true)
        return try {
            check(allowed())
            data.writeInterfaceToken(ShizukuUiProtocol.DESCRIPTOR)
            PrivilegedDeviceParcel.writeRequest(data, request)
            data.writeStrongBinder(
                ShizukuExecutionGuard(
                    { _, _, _ -> active.get() && allowed() },
                    { active.get() && allowed() },
                ),
            )
            check(binder.transact(ShizukuUiProtocol.DEVICE, data, reply, 0))
            reply.readException()
            val status = requireNotNull(reply.readString())
            val target = PrivilegedDeviceParcel.readTarget(reply)
            val fd = reply.readParcelable<SharedMemory>(SharedMemory::class.java.classLoader)
            val screenshot = fd?.let { readCapture(it, target, allowed) }
            val snapshot = PrivilegedSemanticParcel.read(reply)
            AutomationDeviceReply(
                status,
                target,
                screenshot,
                if (request.operation.hasEffect()) {
                    AutomationActionResult(
                        if (request.operation != AutomationDeviceOperation.GESTURE) {
                            AutomationActionStatus.valueOf(status)
                        } else {
                            shizukuActionStatus(status)
                        },
                    )
                } else {
                    null
                },
                snapshot,
            )
        } catch (error: Exception) {
            android.util.Log.w("HelixPrivileged", "Device ${request.operation} failed", error)
            AutomationDeviceReply(
                "PRIVILEGED_DEVICE_FAILED",
                action =
                    if (request.operation.hasEffect()) {
                        AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
                    } else {
                        null
                    },
            )
        } finally {
            active.set(false)
            data.recycle()
            reply.recycle()
        }
    }

    private fun readCapture(
        memory: SharedMemory,
        observed: com.helix.extensions.mobileuse.automation.AutomationDisplayTarget?,
        allowed: () -> Boolean,
    ): AutomationScreenshot =
        memory.use {
            val target = checkNotNull(observed)
            check(it.size.toLong() in 1..VisionLimits.MAX_INPUT_BYTES && allowed())
            val mapping = it.mapReadOnly()
            val bytes =
                try {
                    ByteArray(it.size).also(mapping::get)
                } finally {
                    SharedMemory.unmap(mapping)
                }
            check(allowed())
            AutomationScreenshot(
                "SAVED",
                bytes,
                target.width,
                target.height,
                AutomationNodeBounds(0, 0, target.width, target.height),
            )
        }
}

package com.helix.extensions.mobileuse.automation.backend

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

/** Cleanup only through the original already-connected root helper; never reconnect or replay input. */
@Suppress("TooGenericExceptionCaught")
internal fun restorePrivilegedFocus(
    binder: IBinder,
    checkpoint: FocusRecoveryCheckpoint,
    deadline: Long,
    connected: () -> Boolean,
) {
    val allowed = { connected() && binder.isBinderAlive && SystemClock.elapsedRealtime() <= deadline }
    if (!allowed()) return
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(ShizukuUiProtocol.DESCRIPTOR)
        checkpoint.write(data)
        data.writeLong(deadline)
        data.writeStrongBinder(ShizukuExecutionGuard({ _, _, _ -> allowed() }, allowed))
        check(binder.transact(ShizukuUiProtocol.RESTORE_FOCUS, data, reply, 0))
        reply.readException()
        android.util.Log.i("HelixDisplay", "Root focus cleanup: ${requireNotNull(reply.readString())}")
    } catch (failure: Exception) {
        android.util.Log.w("HelixDisplay", "Root focus cleanup unconfirmed", failure)
    } finally {
        data.recycle()
        reply.recycle()
    }
}

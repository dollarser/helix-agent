package com.helix.extensions.mobileuse.automation.backend

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.helix.extensions.mobileuse.automation.AutomationDisplayLifecycle

/** Live original-call checks. The shell service cannot mint or persist a grant. */
internal class ShizukuExecutionGuard(
    private val allowed: (Int, Int, Int) -> Boolean,
    private val mayFinish: () -> Boolean,
    private val displayLifecycle: ShizukuDisplayLifecycle? = null,
) : Binder() {
    private val focusRecovery = displayLifecycle?.let { ShizukuFocusRecovery() }

    @Suppress("ReturnCount") // Distinct protocol branches enforce peer and parcel identity before callbacks.
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code !in setOf(CHECK, DISPLAY_EVENT, PREVIEW_PACKAGE, FOCUS_CHECKPOINT)) {
            return super.onTransact(code, data, reply, flags)
        }
        check(getCallingUid() in setOf(0, 2000)) { "UNTRUSTED_GUARD_CALLER" }
        data.enforceInterface(DESCRIPTOR)
        if (code == FOCUS_CHECKPOINT) return recordFocusCheckpoint(data, reply)
        if (code == PREVIEW_PACKAGE) {
            val packageName = requireNotNull(data.readString()).also { require(it.length <= 256) }
            require(data.dataAvail() == 0)
            val identity = clearCallingIdentity()
            val accepted =
                try {
                    mayFinish() && displayLifecycle?.permitsPreviewPackage(packageName) == true
                } finally {
                    restoreCallingIdentity(identity)
                }
            reply?.writeNoException()
            reply?.writeInt(if (accepted) 1 else 0)
            return true
        }
        if (code == DISPLAY_EVENT) {
            val state = requireNotNull(AutomationDisplayLifecycle.entries.getOrNull(data.readInt()))
            require(
                state in
                    setOf(
                        AutomationDisplayLifecycle.ACTIVE,
                        AutomationDisplayLifecycle.RELEASED,
                        AutomationDisplayLifecycle.RELEASE_UNKNOWN,
                    ),
            )
            require(data.dataAvail() == 0)
            val identity = clearCallingIdentity()
            try {
                if (state == AutomationDisplayLifecycle.RELEASED) focusRecovery?.released()
                displayLifecycle?.report(state)
            } finally {
                restoreCallingIdentity(identity)
            }
            reply?.writeNoException()
            return true
        }
        val x = data.readInt()
        val y = data.readInt()
        val rotation = data.readInt()
        val finishing = data.readInt() == 1
        require(data.dataAvail() == 0)
        val identity = clearCallingIdentity()
        val accepted =
            try {
                if (finishing) mayFinish() else allowed(x, y, rotation)
            } finally {
                restoreCallingIdentity(identity)
            }
        reply?.writeNoException()
        reply?.writeInt(if (accepted) 1 else 0)
        return true
    }

    private fun recordFocusCheckpoint(
        data: Parcel,
        reply: Parcel?,
    ): Boolean {
        val checkpoint = FocusRecoveryCheckpoint.read(data)
        require(data.dataAvail() == 0)
        focusRecovery?.record(checkpoint)
        reply?.writeNoException()
        return true
    }

    fun discardUnboundDisplayIfInactive() {
        if (!mayFinish()) displayLifecycle?.discardIfNeverBound()
    }

    fun bindDisplayService(
        service: IBinder,
        recover: (FocusRecoveryCheckpoint, Long) -> Unit,
    ) {
        displayLifecycle?.bind(service)
        focusRecovery?.bind(service, recover)
    }

    fun observedDisplayPackage(value: String?) {
        displayLifecycle?.observedPackage(value)
    }

    fun displayRefused(status: String) {
        displayLifecycle?.refused(status)
    }

    fun displayConnectionLost() {
        displayLifecycle?.connectionFailed()
    }

    fun previewService(
        session: String,
        turn: String,
        generation: Long,
    ): IBinder? = if (mayFinish()) displayLifecycle?.previewService(session, turn, generation) else null

    companion object {
        private const val DESCRIPTOR = "com.helix.app.automation.ShizukuGuard"
        private const val CHECK = IBinder.FIRST_CALL_TRANSACTION
        private const val DISPLAY_EVENT = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val PREVIEW_PACKAGE = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val FOCUS_CHECKPOINT = IBinder.FIRST_CALL_TRANSACTION + 3

        @Suppress("TooGenericExceptionCaught") // Missing cleanup evidence cannot change the original tool outcome.
        fun focusCheckpoint(
            binder: IBinder,
            value: FocusRecoveryCheckpoint,
        ) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                value.write(data)
                if (binder.transact(FOCUS_CHECKPOINT, data, reply, 0)) reply.readException()
            } catch (failure: Exception) {
                android.util.Log.w("HelixDisplay", "Focus cleanup checkpoint unavailable", failure)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun permitsPreviewPackage(
            binder: IBinder,
            packageName: String,
        ): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(packageName)
                if (!binder.transact(PREVIEW_PACKAGE, data, reply, 0)) return false
                reply.readException()
                reply.readInt() == 1
            } catch (_: android.os.RemoteException) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        @Suppress("TooGenericExceptionCaught") // UI callback failure never changes the real display effect.
        fun displayEvent(
            binder: IBinder,
            state: AutomationDisplayLifecycle,
        ) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(state.ordinal)
                if (binder.transact(DISPLAY_EVENT, data, reply, 0)) reply.readException()
            } catch (_: android.os.RemoteException) {
                // Presentation unavailable; never alter or retry the original effect.
            } catch (failure: RuntimeException) {
                android.util.Log.w("HelixDisplay", "Lifecycle projection unavailable", failure)
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun check(
            binder: IBinder,
            x: Int = -1,
            y: Int = -1,
            rotation: Int = -1,
            finishing: Boolean = false,
        ): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(x)
                data.writeInt(y)
                data.writeInt(rotation)
                data.writeInt(if (finishing) 1 else 0)
                if (!binder.transact(CHECK, data, reply, 0)) return false
                reply.readException()
                reply.readInt() == 1
            } catch (_: Exception) {
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}

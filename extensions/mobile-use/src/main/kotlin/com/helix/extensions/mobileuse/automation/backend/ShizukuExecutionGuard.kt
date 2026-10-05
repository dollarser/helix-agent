package com.helix.extensions.mobileuse.automation.backend

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/** Live original-call checks. The shell service cannot mint or persist a grant. */
internal class ShizukuExecutionGuard(
    private val allowed: (Int, Int, Int) -> Boolean,
    private val mayFinish: () -> Boolean,
) : Binder() {
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code != CHECK) return super.onTransact(code, data, reply, flags)
        check(getCallingUid() in setOf(0, 2000)) { "UNTRUSTED_GUARD_CALLER" }
        data.enforceInterface(DESCRIPTOR)
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

    companion object {
        private const val DESCRIPTOR = "com.helix.app.automation.ShizukuGuard"
        private const val CHECK = IBinder.FIRST_CALL_TRANSACTION

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

package com.helix.tools.root

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.topjohnwu.superuser.ipc.RootService

internal object RootServiceProtocol {
    const val DESCRIPTOR = "com.helix.tools.root.IRootServiceProbe"
    const val GET_PROCESS_ID = IBinder.FIRST_CALL_TRANSACTION
}

/** Minimal non-daemon RootService used to verify Binder lifecycle before any Root tool exists. */
class HelixRootService : RootService() {
    private val binder =
        object : Binder() {
            init {
                attachInterface(null, RootServiceProtocol.DESCRIPTOR)
            }

            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != RootServiceProtocol.GET_PROCESS_ID) {
                    return super.onTransact(code, data, reply, flags)
                }
                data.enforceInterface(RootServiceProtocol.DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(Process.myPid())
                return true
            }
        }

    override fun onBind(intent: Intent): IBinder = binder
}

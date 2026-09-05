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
    const val EXECUTE_HIGH_LEVEL = IBinder.FIRST_CALL_TRANSACTION + 1
}

/** Non-daemon RootService exposing only the typed, bounded HXA-095 read protocol. */
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
            ): Boolean =
                when (code) {
                    RootServiceProtocol.GET_PROCESS_ID -> {
                        data.enforceInterface(RootServiceProtocol.DESCRIPTOR)
                        reply?.writeNoException()
                        reply?.writeInt(Process.myPid())
                        true
                    }

                    RootServiceProtocol.EXECUTE_HIGH_LEVEL -> {
                        data.enforceInterface(RootServiceProtocol.DESCRIPTOR)
                        val result =
                            RootServiceOperations(
                                this@HelixRootService,
                            ).execute(RootServiceCodec.readRequest(data))
                        reply?.writeNoException()
                        reply?.let { RootServiceCodec.writeResult(it, result) }
                        true
                    }

                    else -> {
                        super.onTransact(code, data, reply, flags)
                    }
                }
        }

    override fun onBind(intent: Intent): IBinder = binder
}

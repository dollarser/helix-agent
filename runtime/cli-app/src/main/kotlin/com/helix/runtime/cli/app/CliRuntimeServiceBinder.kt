package com.helix.runtime.cli.app

import android.os.Binder
import android.os.Parcel

class CliRuntimeServiceBinder(
    private val statusProvider: () -> String,
    private val callerVerifier: (Int) -> Boolean,
) : Binder() {
    @Suppress("ReturnCount") // Unknown transaction, rejected caller, and success are distinct outcomes.
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code != CliRuntimeProtocol.TRANSACTION_STATUS ||
            reply == null
        ) {
            return super.onTransact(code, data, reply, flags)
        }
        data.enforceInterface(CliRuntimeProtocol.DESCRIPTOR)
        if (!callerVerifier(getCallingUid())) {
            reply.writeInt(CliRuntimeProtocol.REPLY_CALLER_MISMATCH)
            return true
        }
        val status = statusProvider()
        require(
            status.encodeToByteArray().size <= CliRuntimeProtocol.MAX_STATUS_BYTES,
        ) { "CLI status exceeds wire limit" }
        reply.writeInt(CliRuntimeProtocol.REPLY_OK)
        reply.writeString(status)
        return true
    }
}

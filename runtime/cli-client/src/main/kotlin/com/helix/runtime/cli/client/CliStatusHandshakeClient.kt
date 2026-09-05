package com.helix.runtime.cli.client

import android.os.IBinder
import android.os.Parcel

object CliStatusHandshakeClient {
    sealed interface Outcome {
        data class Ok(val status: CliRuntimeStatus) : Outcome
        data object CallerMismatch : Outcome
        data object Failed : Outcome
    }

    fun transact(binder: IBinder): Outcome {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
            if (!binder.transact(CliRuntimeProtocol.TRANSACTION_STATUS, data, reply, 0)) return Outcome.Failed
            when (reply.readInt()) {
                CliRuntimeProtocol.REPLY_OK -> {
                    val document = reply.readString() ?: return Outcome.Failed
                    runCatching { Outcome.Ok(CliRuntimeStatusCodec.decode(document)) }.getOrDefault(Outcome.Failed)
                }
                CliRuntimeProtocol.REPLY_CALLER_MISMATCH -> Outcome.CallerMismatch
                else -> Outcome.Failed
            }
        } catch (_: RuntimeException) {
            Outcome.Failed
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

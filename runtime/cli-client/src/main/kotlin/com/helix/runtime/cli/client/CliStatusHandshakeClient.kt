package com.helix.runtime.cli.client

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

object CliStatusHandshakeClient {
    sealed interface Outcome {
        data class Ok(
            val status: CliRuntimeStatus,
        ) : Outcome

        data object CallerMismatch : Outcome

        data object Failed : Outcome
    }

    fun transact(binder: IBinder): Outcome {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
            if (binder.transact(CliRuntimeProtocol.TRANSACTION_STATUS, data, reply, 0)) {
                decodeReply(reply)
            } else {
                Outcome.Failed
            }
        } catch (_: RemoteException) {
            Outcome.Failed
        } catch (_: RuntimeException) {
            Outcome.Failed
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun decodeReply(reply: Parcel): Outcome =
        when (reply.readInt()) {
            CliRuntimeProtocol.REPLY_OK -> {
                val document = reply.readString()
                if (document == null) {
                    Outcome.Failed
                } else {
                    runCatching { Outcome.Ok(CliRuntimeStatusCodec.decode(document)) }.getOrDefault(Outcome.Failed)
                }
            }

            CliRuntimeProtocol.REPLY_CALLER_MISMATCH -> {
                Outcome.CallerMismatch
            }

            else -> {
                Outcome.Failed
            }
        }
}

package com.helix.runtime.cli.client

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.helix.core.model.ModelEvent
import java.io.IOException

/** Optional preview channel. Only the existing durable result can complete a model call. */
internal object CliModelProgressClient {
    fun read(
        binder: IBinder,
        jobId: String,
        offset: Int,
    ): List<ModelEvent>? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
            data.writeString(jobId)
            data.writeInt(offset)
            if (!binder.transact(CliRuntimeProtocol.TRANSACTION_JOB_PROGRESS, data, reply, 0) ||
                reply.readInt() != CliRuntimeProtocol.REPLY_OK
            ) {
                null
            } else {
                val hash = requireNotNull(reply.readString())
                val pipe =
                    requireNotNull(
                        reply.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader),
                    )
                val bytes = CliPfdChannel.read(pipe)
                require(cliPayloadSha256(bytes) == hash)
                CliModelProgressCodec.decode(bytes)
            }
        } catch (_: RemoteException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

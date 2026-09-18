package com.helix.runtime.proot.client

import android.os.Parcel
import android.os.RemoteException
import com.helix.runtime.proot.core.JobLogPage
import com.helix.runtime.proot.ipc.ProotLogWire
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol

/** Observation uses only the approved submission's Binder: no binding or process startup. */
class ProotLogClient {
    @Suppress("SwallowedException") // null explicitly means preview unavailable, not execution success
    fun read(
        jobId: String,
        inputManifestSha256: String,
        cursor: String?,
    ): JobLogPage? {
        val binder = ProotLogConnections.find(jobId, inputManifestSha256) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
            data.writeInt(ProotLogWire.VERSION)
            data.writeString(jobId)
            data.writeString(inputManifestSha256)
            data.writeString(cursor)
            if (binder.transact(ProotRuntimeProtocol.TX_JOB_LOG_READ, data, reply, 0)) {
                ProotLogWire.read(reply)
            } else {
                null
            }
        } catch (_: RemoteException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

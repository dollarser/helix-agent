package com.helix.runtime.cli.client

import android.os.Parcel
import android.os.RemoteException
import com.helix.core.model.ModelErrorCode

/** Explicit refresh only; callers run off the UI thread and own the temporary binding. */
class CliModelCatalogClient(
    private val supervisor: CliRuntimeSupervisor,
) {
    fun fetch(): CliModelCatalog {
        val connection = supervisor.openConnection()
        if (connection !is CliRuntimeConnection.Opened) {
            return CliModelCatalog.Failed(ModelErrorCode.TRANSPORT, true)
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
            if (!connection.binder.transact(CliRuntimeProtocol.TRANSACTION_MODEL_CATALOG, data, reply, 0)) {
                CliModelCatalog.Failed(ModelErrorCode.PROTOCOL, false)
            } else if (reply.readInt() != CliRuntimeProtocol.REPLY_OK) {
                CliModelCatalog.Failed(ModelErrorCode.PROTOCOL, false)
            } else {
                CliModelCatalogCodec.decode(requireNotNull(reply.readString()))
            }
        } catch (_: RemoteException) {
            CliModelCatalog.Failed(ModelErrorCode.TRANSPORT, true)
        } catch (_: RuntimeException) {
            CliModelCatalog.Failed(ModelErrorCode.PROTOCOL, false)
        } finally {
            data.recycle()
            reply.recycle()
            supervisor.closeConnection(connection)
        }
    }
}

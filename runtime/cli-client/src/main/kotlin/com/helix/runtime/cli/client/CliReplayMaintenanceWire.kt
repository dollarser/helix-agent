package com.helix.runtime.cli.client

import android.os.IBinder
import android.os.Parcel

/** Validates bounded maintenance replies before the application treats any deletion as confirmed. */
internal object CliReplayMaintenanceWire {
    fun page(
        binder: IBinder,
        after: String?,
    ): CliReplayPage {
        after?.let(CliReplayMaintenance::requireHash)
        val page =
            exchange(binder, CliRuntimeProtocol.TRANSACTION_REPLAY_PAGE, { it.writeString(after) }) {
                CliReplayMaintenance.decode(requireNotNull(it.readString()))
            }
        require(after == null || page.entries.all { it.key > after })
        return page
    }

    fun prune(
        binder: IBinder,
        entries: List<CliReplayEntry>,
    ): CliReplayPruneResult {
        val document = CliReplayMaintenance.encode(CliReplayPage(entries, null))
        return exchange(binder, CliRuntimeProtocol.TRANSACTION_REPLAY_PRUNE, { it.writeString(document) }) { reply ->
            require(reply.dataAvail() == 24)
            val deleted = reply.readInt()
            val retained = reply.readInt()
            val failed = reply.readInt()
            val bytes = reply.readLong()
            val busy = reply.readInt()
            require(deleted >= 0 && retained >= 0 && failed >= 0 && bytes >= 0 && busy in 0..1)
            require(deleted.toLong() + retained + failed == entries.size.toLong())
            require(bytes <= entries.sumOf { it.bytes })
            require(busy == 0 || (deleted == 0 && failed == 0 && bytes == 0L))
            CliReplayPruneResult(deleted, retained, failed, bytes, busy == 1)
        }
    }

    private fun <T> exchange(
        binder: IBinder,
        code: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "REPLAY_MAINTENANCE_UNSUPPORTED" }
            require(reply.dataAvail() in 4..(CliReplayMaintenance.MAX_WIRE_CHARS * 2 + 16))
            check(reply.readInt() == CliRuntimeProtocol.REPLY_OK) { "REPLAY_MAINTENANCE_REJECTED" }
            return read(reply).also { require(reply.dataAvail() == 0) }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

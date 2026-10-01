package com.helix.runtime.cli.app

import android.os.Parcel
import com.helix.runtime.cli.client.CliReplayMaintenance
import com.helix.runtime.cli.client.CliRuntimeProtocol

/** Called only after the existing caller and interface checks. */
internal class CliReplayMaintenanceBinder(
    private val maintenance: AntigravityReplayMaintenance,
    private val runner: CodexPayloadJobRunner,
) {
    fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel,
    ) {
        require(data.dataAvail() <= CliReplayMaintenance.MAX_WIRE_CHARS * 2 + 16)
        when (code) {
            CliRuntimeProtocol.TRANSACTION_REPLAY_PAGE -> {
                val after = data.readString()
                require(data.dataAvail() == 0)
                val page = maintenance.page(after)
                reply.writeInt(CliRuntimeProtocol.REPLY_OK)
                reply.writeString(CliReplayMaintenance.encode(page))
            }

            CliRuntimeProtocol.TRANSACTION_REPLAY_PRUNE -> {
                val page = CliReplayMaintenance.decode(requireNotNull(data.readString()))
                require(page.nextAfter == null && data.dataAvail() == 0)
                val result = runner.pruneReplay(page.entries, maintenance)
                reply.writeInt(CliRuntimeProtocol.REPLY_OK)
                reply.writeInt(result.deleted)
                reply.writeInt(result.retained)
                reply.writeInt(result.failed)
                reply.writeLong(result.deletedBytes)
                reply.writeInt(if (result.busy) 1 else 0)
            }

            else -> {
                error("Unknown replay operation")
            }
        }
    }
}

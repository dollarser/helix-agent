package com.helix.runtime.cli.client

import android.os.IBinder

/** Explicit maintenance only. No model request, account access, cancellation or automatic retry. */
class CliReplayMaintenanceClient(
    private val supervisor: CliRuntimeSupervisor,
) {
    private companion object {
        val calls = CliReplayCalls()
    }

    fun page(after: String?): CliReplayPage = connected { CliReplayMaintenanceWire.page(it, after) }

    fun prune(entries: List<CliReplayEntry>): CliReplayPruneResult =
        connected { CliReplayMaintenanceWire.prune(it, entries) }

    private fun <T> connected(action: (IBinder) -> T): T =
        calls.call {
            val connection = supervisor.openConnection()
            check(connection is CliRuntimeConnection.Opened) { "REPLAY_RUNTIME_UNAVAILABLE" }
            try {
                action(connection.binder)
            } finally {
                supervisor.closeConnection(connection)
            }
        }
}

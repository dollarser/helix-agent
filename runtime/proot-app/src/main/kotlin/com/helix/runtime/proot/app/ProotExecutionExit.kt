package com.helix.runtime.proot.app

import com.helix.runtime.proot.ipc.ProotJobState

/** Stop intent is not process exit; an unknown process must never be published as safely terminated. */
internal object ProotExecutionExit {
    fun terminalState(
        requested: ProotJobState,
        processAlive: Boolean,
    ): ProotJobState = if (processAlive) ProotJobState.ORPHANED else requested

    /** Retain the original worker and its Runtime reservation until real exit, even after interruption. */
    fun awaitExit(
        alive: () -> Boolean,
        stop: () -> Unit,
        await: () -> Unit,
    ) {
        var interrupted = Thread.interrupted()
        try {
            while (alive()) {
                try {
                    stop()
                    await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

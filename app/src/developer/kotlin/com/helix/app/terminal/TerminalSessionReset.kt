package com.helix.app.terminal

import kotlinx.coroutines.delay

/** Stop the captured original identities; only proven exits may be removed. No file/environment deletion. */
internal suspend fun resetTerminalSessions(terminal: ManualTerminal): List<ManualTerminal.State> {
    val original = terminal.sessions()
    var observed = original.map { if (it.canSettle) it else terminal.stop(it.sessionId) }
    val deadline = System.nanoTime() + 15_000_000_000L
    while (observed.any { !it.canSettle && it.phase != "UNKNOWN" } && System.nanoTime() < deadline) {
        delay(100)
        observed = original.map { terminal.query(it.sessionId) }
    }
    observed.filter { it.canSettle }.forEach { terminal.settle(it.sessionId) }
    return terminal.sessions()
}

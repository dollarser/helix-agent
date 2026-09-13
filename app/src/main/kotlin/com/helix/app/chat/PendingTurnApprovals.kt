package com.helix.app.chat

/** Pending cards belong to a Turn, never to whichever session happens to be visible. */
internal class PendingTurnApprovals {
    private val owners = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun register(
        approvalId: String,
        turnId: String,
    ) {
        owners[approvalId] = turnId
    }

    fun forTurn(turnId: String): List<String> = owners.filterValues { it == turnId }.keys.toList()

    fun finishTurn(turnId: String) {
        owners.entries.removeIf { it.value == turnId }
    }
}

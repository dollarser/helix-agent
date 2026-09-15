package com.helix.app.chat

/**
 * Pending cards belong to a Turn (and that turn's session), never to whichever session
 * happens to be visible. The session fact exists for the session-scoped stop cancel: a
 * direct per-call dispatch is never admitted as an active turn, but its pending card is
 * still stopped with its session.
 */
internal class PendingTurnApprovals {
    private val owners = java.util.concurrent.ConcurrentHashMap<String, Owner>()

    private data class Owner(
        val turnId: String,
        val sessionId: String,
    )

    fun register(
        approvalId: String,
        turnId: String,
        sessionId: String,
    ) {
        owners[approvalId] = Owner(turnId, sessionId)
    }

    fun forTurn(turnId: String): List<String> = owners.filterValues { it.turnId == turnId }.keys.toList()

    /** The pending card ids owned by turns of [sessionId] (the session-scoped stop cancel). */
    fun forSession(sessionId: String): List<String> = owners.filterValues { it.sessionId == sessionId }.keys.toList()

    fun finishTurn(turnId: String) {
        owners.entries.removeIf { it.value.turnId == turnId }
    }
}

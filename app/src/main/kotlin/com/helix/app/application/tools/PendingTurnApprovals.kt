package com.helix.app.application.tools

/** Live request identities only. Durable broker/call records authorize every decision. */
internal class PendingTurnApprovals {
    data class Identity(
        val turnId: String,
        val callId: String,
    )

    private class Entry(
        val identity: Identity,
    ) {
        val claimed =
            java.util.concurrent.atomic
                .AtomicBoolean()
    }

    private val owners = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun register(
        approvalId: String,
        turnId: String,
        callId: String,
    ) {
        val identity = Identity(turnId, callId)
        val existing = owners.putIfAbsent(approvalId, Entry(identity))
        check(existing == null || existing.identity == identity) { "Approval request identity changed" }
    }

    fun find(approvalId: String): Identity? = owners[approvalId]?.identity

    fun claim(
        turnId: String,
        approvalId: String,
    ): Identity? {
        val entry = owners[approvalId]?.takeIf { it.identity.turnId == turnId } ?: return null
        // A claimed UI action still belongs to this Turn until the broker settles: stop must
        // retain the ability to cancel its pending authorization during the decision handoff.
        return if (entry.claimed.compareAndSet(false, true) && owners[approvalId] === entry) {
            entry.identity
        } else {
            null
        }
    }

    fun remove(approvalId: String) {
        owners.remove(approvalId)
    }

    fun forTurn(turnId: String): List<String> = owners.filterValues { it.identity.turnId == turnId }.keys.toList()

    fun finishTurn(turnId: String) {
        owners.entries.removeIf { it.value.identity.turnId == turnId }
    }
}

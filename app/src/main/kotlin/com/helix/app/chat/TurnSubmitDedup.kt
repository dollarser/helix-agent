package com.helix.app.chat

/**
 * The idempotency ledger behind [AgentTurnHost.startTurn]'s client-request dedup (research doc
 * section 34; HX2-01 §2e): a stable [clientRequestId] maps to the turn it started, so a re-driven
 * submission with the same id returns the already-started turn instead of starting a second one.
 *
 * Pure (no storage / provider / coroutine reference) so it is unit-testable on the JVM, where the
 * heavy [ChatService] (concrete Room-backed deps, no Robolectric) cannot be constructed. The caller
 * invokes [alreadyStarted] and [record] together under its own turn-start gate, so the check-then-
 * record pair is atomic with respect to other start attempts; the internal lock only guards the
 * map against concurrent map mutation.
 *
 * Bounded: once the map holds [capacity] entries, the OLDEST-inserted entry is evicted on the next
 * record, so a long-lived process does not grow the map without bound. A client-request id is never
 * legitimately reused, so an eviction only drops an id no future submission can re-drive.
 */
internal class TurnSubmitDedup(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val byClientRequestId: LinkedHashMap<String, String> = LinkedHashMap()

    /**
     * The turn [clientRequestId] already started, or null when the id is new (the caller proceeds
     * to start and then [record]s it). A returned id means the caller must NOT start a new turn —
     * it returns that id.
     */
    fun alreadyStarted(clientRequestId: String): String? = synchronized(lock) { byClientRequestId[clientRequestId] }

    /** Records that [clientRequestId] started [turnId], evicting the oldest entry when the map is full. */
    fun record(
        clientRequestId: String,
        turnId: String,
    ) {
        synchronized(lock) {
            val isNew = !byClientRequestId.containsKey(clientRequestId)
            if (isNew && byClientRequestId.size >= capacity) {
                byClientRequestId.remove(byClientRequestId.keys.first())
            }
            byClientRequestId[clientRequestId] = turnId
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 512
    }
}

package com.helix.app.voice

/** Bounded, process-local endpoint cooldown. Never retains another account's error details or IDs. */
internal object SpeechRetryCooldown {
    private data class Wait(
        val started: Long,
        val millis: Long,
    )

    private val waits = linkedMapOf<String, Wait>()

    @Synchronized
    fun record(
        endpoint: String,
        millis: Long,
        nowNanos: Long = System.nanoTime(),
    ) {
        if (millis <= 0) return
        waits[endpoint] = Wait(nowNanos, maxOf(millis, remaining(endpoint, nowNanos) ?: 0))
        while (waits.size > 64) waits.remove(waits.keys.first())
    }

    @Synchronized
    fun remaining(
        endpoint: String,
        nowNanos: Long = System.nanoTime(),
    ): Long? {
        val wait = waits[endpoint] ?: return null
        val remaining = wait.millis - ((nowNanos - wait.started) / 1_000_000).coerceAtLeast(0)
        return if (remaining > 0) {
            remaining
        } else {
            waits.remove(endpoint)
            null
        }
    }
}

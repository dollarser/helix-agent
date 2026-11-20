package com.helix.app.account

import com.helix.provider.api.wire.HttpFailureDetails

/** Session-owned, monotonic deadlines only. Never retains credentials or another account's error text. */
internal class AccountRequestCooldown(
    private val monotonicMillis: () -> Long,
) {
    private val deadlines = LinkedHashMap<Pair<Long, String>, Wait>()

    @Synchronized fun check(
        revision: Long,
        endpoint: String,
    ) {
        val now = monotonicMillis()
        deadlines.entries.removeAll { it.value.remaining(now) == 0L }
        val remaining = deadlines[revision to endpoint]?.remaining(now) ?: return
        throw AccountRequestFailure(HttpFailureDetails(429, retryAfterMillis = remaining))
    }

    @Synchronized fun remainingMillis(
        revision: Long,
        endpoint: String,
    ): Long = deadlines[revision to endpoint]?.remaining(monotonicMillis()) ?: 0L

    @Synchronized fun record(
        revision: Long,
        endpoint: String,
        details: HttpFailureDetails,
    ) {
        val wait = details.retryAfterMillis?.takeIf { details.status == 429 && it > 0 } ?: return
        val now = monotonicMillis()
        val key = revision to endpoint
        deadlines[key] = Wait(now, maxOf(deadlines[key]?.remaining(now) ?: 0, wait))
        while (deadlines.size > 64) deadlines.remove(deadlines.keys.first())
    }

    private data class Wait(
        val started: Long,
        val duration: Long,
    ) {
        fun remaining(now: Long): Long = (duration - (now - started).coerceAtLeast(0)).coerceAtLeast(0)
    }
}

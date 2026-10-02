package com.helix.tools.automation

import java.time.Duration
import java.time.Instant

/** Recheck long grants without overflowing Handler uptime; rounding never expires a grant early. */
internal fun automationExpiryDelayMillis(
    now: Instant,
    expiresAt: Instant,
): Long? =
    when {
        expiresAt == Instant.MAX -> {
            null
        }

        !now.isBefore(expiresAt) -> {
            0L
        }

        else -> {
            val remaining = Duration.between(now, expiresAt)
            val daySeconds = 86_400L
            if (remaining.seconds >= daySeconds) {
                daySeconds * 1_000
            } else {
                remaining.seconds * 1_000 + (remaining.nano.toLong() + 999_999) / 1_000_000
            }
        }
    }

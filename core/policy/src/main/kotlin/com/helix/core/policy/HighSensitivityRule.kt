package com.helix.core.policy

import com.helix.core.model.NormalizedEndpoint
import java.time.Duration
import java.time.Instant

/**
 * The only rule durations ADVANCED may store for high-sensitivity egress (ADR-0005; provider
 * doc section 2.6): 1 hour, 24 hours, 7 days or 30 days. Default 24 hours, hard cap 30 days.
 * "Permanent", "forever" and custom longer values are unconstructable; renewal is always a new
 * explicit user action (a new rule), never a sliding extension of this one.
 */
enum class RuleDuration {
    HOURS_1,
    HOURS_24,
    DAYS_7,
    DAYS_30,
    ;

    val duration: Duration
        get() =
            when (this) {
                HOURS_1 -> Duration.ofHours(1)
                HOURS_24 -> Duration.ofHours(24)
                DAYS_7 -> Duration.ofDays(7)
                DAYS_30 -> Duration.ofDays(30)
            }

    companion object {
        val DEFAULT: RuleDuration = HOURS_24
    }
}

/**
 * An ADVANCED high-sensitivity egress rule (ADR-0005): exactly bound to a stable
 * Provider/MCP ID + normalized origin + data category + user scope + validity window, viewable
 * and immediately revocable.
 *
 * Invariants enforced by construction:
 * - only [DataSensitivity.SENSITIVE] may carry a stored rule (FORBIDDEN is always denied,
 *   NORMAL never needs one);
 * - the TTL is exactly one of [RuleDuration] — nothing longer, nothing custom;
 * - [scope] is mandatory: there is no unscoped (blanket) grant.
 *
 * Invariants enforced at evaluation time (see PolicyEngine):
 * - `createdAt <= now < expiresAt` — the window is checked against the live clock, so clock
 *   rollback (`now < createdAt`) fails closed like expiry;
 * - every binding field must match the current call exactly (target, origin, category, scope);
 *   any changed field re-gates the call to per-call confirmation;
 * - no sliding renewal: the window never moves because a call happened.
 */
data class HighSensitivityRule(
    val target: EgressTarget,
    val origin: NormalizedEndpoint,
    val dataCategory: DataSensitivity,
    val scope: UserScope,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(dataCategory == DataSensitivity.SENSITIVE) {
            "only SENSITIVE data may carry a stored rule (got $dataCategory)"
        }
        val ttl = Duration.between(createdAt, expiresAt)
        require(!ttl.isNegative) { "expiresAt must not be before createdAt" }
        val allowed = RuleDuration.entries.map { it.duration }
        require(allowed.contains(ttl)) {
            "rule TTL must be exactly 1h, 24h, 7d or 30d (ADR-0005); got $ttl"
        }
    }

    /** The rule's stored duration, derivable because only the four fixed TTLs exist. */
    val duration: RuleDuration
        get() = RuleDuration.entries.first { it.duration == Duration.between(createdAt, expiresAt) }

    companion object {
        /** Creates a rule with the ADR-0005 default TTL (24 hours). */
        fun withDefaultTtl(
            target: EgressTarget,
            origin: NormalizedEndpoint,
            scope: UserScope,
            createdAt: Instant,
        ): HighSensitivityRule =
            HighSensitivityRule(
                target = target,
                origin = origin,
                dataCategory = DataSensitivity.SENSITIVE,
                scope = scope,
                createdAt = createdAt,
                expiresAt = createdAt.plus(RuleDuration.DEFAULT.duration),
            )
    }
}

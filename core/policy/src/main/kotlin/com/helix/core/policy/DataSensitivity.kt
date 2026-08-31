package com.helix.core.policy

/**
 * Data sensitivity class of what an egress request actually contains (provider doc section 2.6;
 * ADR-0005; security doc section 7.2). The category is computed by the trusted execution path
 * from what the request carries — it is never model- or MCP-declared.
 */
enum class DataSensitivity {
    /** Ordinary content: the user's active input of this turn, public web references. */
    NORMAL,

    /**
     * High-sensitivity content: contacts, notification bodies, precise location, file bodies,
     * browser page content, accessibility content. STANDARD confirms per call (no permanent
     * allow); ADVANCED may hold an exactly-bound, time-boxed, revocable rule.
     */
    SENSITIVE,

    /**
     * Never-send content: API keys, OAuth tokens, cookies, passwords, verification codes,
     * authentication fields, CLI credentials. Rejected in both profiles; no rule, profile or
     * expert setting can release it.
     */
    FORBIDDEN,
}

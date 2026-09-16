package com.helix.core.model

/**
 * One CUSTOM operation rule value (ADR-PERMISSIONS-001 section 1.2: 允许 / 询问 / 禁止).
 *
 * Merge order when a call carries several effects (ADR section 1.2 / 2): any DENY refuses
 * the WHOLE call (never execute the allowed part first); otherwise any ASK produces ONE
 * precise approval for the whole call; only an all-ALLOW footprint skips the card. The
 * `rm -rf` command rule can only ADD an approval requirement on top of this, never remove a
 * DENY, and a single approval can never lift a DENY — only a user settings change can.
 */
enum class OperationRule {
    /** The effect executes without a per-call confirmation card (the hard policy gates still apply). */
    ALLOW,

    /** The effect requires one precise per-call approval for the whole call. */
    ASK,

    /** The effect is refused outright: no card, no proof consumption, no per-call bypass. */
    DENY,
}

package com.helix.core.model

/**
 * The tool-layer availability state (ADR-PERMISSIONS-001 section 1.1). Two states only: there
 * is no tool-level ASK/ALLOW, no "ASK before disable" memory, and the old three-state
 * preference is replaced, not kept alongside.
 *
 * ENABLED merely makes the tool eligible to participate in a session; whether a call asks is
 * decided exclusively by the session permission mode / CUSTOM rules. A single
 * approval-card "deny" never changes this state. Disabling a tool covers exactly its trusted
 * identity and its aliases — it is not the same as an operation DENY (disabling `write` does
 * not forbid `edit` or Shell writes; those are operation rules).
 */
enum class ToolAvailabilityState {
    /** The tool may participate in the session: exposed to the model and accepted at the execution entry. */
    ENABLED,

    /**
     * Removed from the model schema, tools.search results and the session exposure window;
     * the execution entry still refuses a directly constructed legacy call. Disabling does
     * not unload the capability and does not affect other sessions or manual operation.
     */
    DISABLED,
}

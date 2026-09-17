package com.helix.core.model

/**
 * The scope of a tool availability state (ADR-PERMISSIONS-001 section 1.1). GLOBAL <
 * WORKSPACE < SESSION: an OUTER disable always wins (a session ENABLED can never silently
 * override a global or workspace DISABLED); otherwise the narrowest explicit state wins,
 * defaulting to ENABLED. The `effectiveAvailability` resolver in core/policy applies this
 * precedence from a [ToolAvailabilityStates] triple.
 *
 * [scopeRef] semantics: empty for GLOBAL, the workspace `directoryRef` for WORKSPACE, the
 * session id for SESSION. The scope is part of the row identity, so one tool identity carries
 * at most one state per scope and "reset to default" is a row delete (two states only).
 */
enum class ToolAvailabilityScope {
    /** Applies to every workspace and session of the app. */
    GLOBAL,

    /** Applies to one bound workspace directory (and its sessions). */
    WORKSPACE,

    /** Applies to one session only. */
    SESSION,
}

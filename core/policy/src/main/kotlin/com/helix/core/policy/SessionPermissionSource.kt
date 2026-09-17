package com.helix.core.policy

import com.helix.core.model.ToolAvailabilityStates

/**
 * The live read seam for the session authorization config (ADR-PERMISSIONS-001 section 2 step 4).
 *
 * The dispatcher reads the EFFECTIVE config for one session through this seam and passes it to
 * the single [SessionPermissionResolver] — the production implementation is the storage
 * repository: a stored per-session row wins, a missing row falls back to the app default (a
 * fresh install compiles to READ_ONLY; never a silent widen).
 *
 * Only a user UI action may change what this seam returns (ADR section 4); the model, web pages,
 * MCP, Skill and scripts have no path into it.
 */
fun interface SessionPermissionSource {
    fun configFor(sessionId: String): SessionPermissionConfig
}

/**
 * The live read seam for tool availability (ADR-PERMISSIONS-001 section 1.1).
 *
 * [statesFor] returns the raw stored triple for one tool identity (canonical origin ref, tool
 * name, scope); the folding into the single effective state — outer disable always wins, else
 * the narrowest explicit state, default ENABLED — is [SessionPermissionResolver.effectiveAvailability].
 * A tool disabled at ANY scope is removed from the model schema, discovery and the loaded window
 * by the same predicate instance, and a call on it is refused before any approval surface
 * (section 2 step 2).
 */
fun interface ToolAvailabilitySource {
    fun statesFor(
        sourceRef: String,
        toolName: String,
        sessionId: String?,
        workspaceRef: String?,
    ): ToolAvailabilityStates
}

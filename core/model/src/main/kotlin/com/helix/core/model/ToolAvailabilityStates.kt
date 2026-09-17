package com.helix.core.model

/**
 * The stored availability states of ONE tool identity across the three scopes
 * (ADR-PERMISSIONS-001 section 1.1). A null slot means "no explicit state stored at that
 * scope" — NOT "explicitly enabled". The `effectiveAvailability` resolver in core/policy
 * folds this triple into the single effective state (outer disable always wins).
 */
data class ToolAvailabilityStates(
    val global: ToolAvailabilityState? = null,
    val workspace: ToolAvailabilityState? = null,
    val session: ToolAvailabilityState? = null,
)

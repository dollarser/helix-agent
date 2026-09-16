package com.helix.app.approval

import com.helix.core.model.ToolAvailabilityStates
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.policy.SessionPermissionSource
import com.helix.core.policy.ToolAvailabilitySource
import com.helix.core.storage.repository.SessionPermissionConfigRepository
import com.helix.core.storage.repository.ToolAvailabilityRepository

/**
 * The app's live read seams for the session authorization (HXA-209 B3, ADR-PERMISSIONS-001
 * section 2 step 4): ONE adapter over the B2 storage repositories, wired into the dispatcher
 * and the exposure surfaces so they all read the SAME live state.
 *
 * [SessionPermissionSource.configFor] is the effective-config read: a stored per-session row
 * wins, a missing row falls back to the app default (a fresh install compiles to READ_ONLY —
 * the lazy default, never a silent widen). [ToolAvailabilitySource.statesFor] returns the raw
 * stored availability triple; the dispatcher and the exposure predicate fold it with the single
 * `effectiveAvailability` (outer disable always wins) so they cannot disagree.
 *
 * This service READS only. Writing a config or an availability state is a user UI action
 * (ADR section 4) that goes through the repositories directly.
 */
class SessionPermissionService(
    private val configs: SessionPermissionConfigRepository,
    private val availability: ToolAvailabilityRepository,
) : SessionPermissionSource,
    ToolAvailabilitySource {
    override fun configFor(sessionId: String): SessionPermissionConfig =
        configs.forSession(sessionId) ?: configs.appDefault()

    override fun statesFor(
        sourceRef: String,
        toolName: String,
        sessionId: String?,
        workspaceRef: String?,
    ): ToolAvailabilityStates = availability.statesFor(sourceRef, toolName, sessionId, workspaceRef)
}

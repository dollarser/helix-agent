package com.helix.app.approval

import com.helix.core.model.ToolAvailabilityState
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
 * HXA-209 C1/C2 (one availability read for every execution entry and exposure surface): the
 * WORKSPACE slot is resolved against the session's OWN trusted workspace ([workspaceFor]) —
 * never only against the workspace a call's scope happens to carry. Most tool calls carry no
 * scope at all, so the session's workspace is the only reliable anchor; a call that DOES
 * assert a trusted platform workspace (automation/root scopes) may operate in a second
 * workspace, and that workspace's rows are folded in as well — a disable in EITHER workspace
 * wins, so the execution entry is always at least as strict as the session-scoped exposure
 * surfaces (section 1.1: 复用同一可用性判定; a call can never claim its way out of a stored
 * disable).
 *
 * This service READS only. Writing a config or an availability state is a user UI action
 * (ADR section 4) that goes through the repositories directly.
 */
class SessionPermissionService(
    private val configs: SessionPermissionConfigRepository,
    private val availability: ToolAvailabilityRepository,
    private val workspaceFor: (sessionId: String) -> String?,
    private val sourceAvailable: (String, String?) -> Boolean = { _, _ -> true },
) : SessionPermissionSource,
    ToolAvailabilitySource {
    override fun configFor(sessionId: String): SessionPermissionConfig =
        configs.forSession(sessionId) ?: configs.appDefault()

    override fun statesFor(
        sourceRef: String,
        toolName: String,
        sessionId: String?,
        workspaceRef: String?,
    ): ToolAvailabilityStates {
        if (!sourceAvailable(sourceRef, sessionId)) {
            return ToolAvailabilityStates(global = ToolAvailabilityState.DISABLED)
        }
        return workspaceStates(sourceRef, toolName, sessionId, workspaceRef)
    }

    private fun workspaceStates(
        sourceRef: String,
        toolName: String,
        sessionId: String?,
        workspaceRef: String?,
    ): ToolAvailabilityStates {
        val sessionWorkspace = sessionId?.let { workspaceFor(it) }
        if (sessionWorkspace == null || sessionWorkspace == workspaceRef) {
            return availability.statesFor(sourceRef, toolName, sessionId, sessionWorkspace ?: workspaceRef)
        }
        // The call asserts a trusted second workspace: read both, and a disable in EITHER
        // wins — the execution entry must never be looser than the session's exposure.
        val sessionStates = availability.statesFor(sourceRef, toolName, sessionId, sessionWorkspace)
        val callStates = availability.statesFor(sourceRef, toolName, sessionId, workspaceRef)
        return sessionStates.copy(workspace = foldedWorkspace(sessionStates.workspace, callStates.workspace))
    }

    /** DISABLED wins over any combination; otherwise the one explicit state (two states total). */
    private fun foldedWorkspace(
        session: ToolAvailabilityState?,
        call: ToolAvailabilityState?,
    ): ToolAvailabilityState? =
        when {
            session == ToolAvailabilityState.DISABLED || call == ToolAvailabilityState.DISABLED -> {
                ToolAvailabilityState.DISABLED
            }

            else -> {
                session ?: call
            }
        }
}

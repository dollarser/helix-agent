package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.ToolApprovalPreferenceSource
import com.helix.core.policy.ToolApprovalResolver
import com.helix.core.storage.repository.ToolApprovalPreferenceRepository

/**
 * The user's standing tool-approval preferences (HXA-200, ADR-0052) — the app's single
 * application service over the durable [ToolApprovalPreferenceRepository].
 *
 * Write path: only this service sets and removes preferences ([set]/[remove]); the model, Skill,
 * MCP and A2A never reach the store. The future settings screen and the approval card call it
 * (HXA-201); the device tests drive it so they exercise the production write path.
 *
 * Read path: it implements [ToolApprovalPreferenceSource] — the live read seam the ToolDispatcher
 * (pre-start re-resolution) and the Registry model-exposure filter both parse, so a preference
 * changed while a call is queued is resolved against the same current store by every surface
 * (ADR-0052 point 7). [effectiveFor] collapses the applicable records with the ONE resolver the
 * dispatcher uses, so exposure and execution can never disagree on the effective value.
 */
class ToolApprovalPreferenceService(
    private val repository: ToolApprovalPreferenceRepository,
) : ToolApprovalPreferenceSource {
    override fun effectiveFor(
        sourceRef: String,
        toolName: String,
        contractHash: String?,
        sessionId: String?,
        workspaceRef: String?,
    ): EffectiveToolPreference =
        ToolApprovalResolver.effectivePreference(
            repository.applicable(sourceRef, toolName, sessionId, workspaceRef),
            contractHash,
        )

    /**
     * Sets (or updates) one tool's preference in one scope, returning the storage id. [contractHash]
     * binds an ALLOW to the descriptor it was granted for (null for ASK/DENY); a later contract
     * change invalidates that ALLOW at read time while ASK/DENY stay live (point 6).
     *
     * An ALLOW is meaningless without that contract binding — the resolver drops a contract-less
     * ALLOW at read time, so it would silently never take effect. We reject it at the write boundary
     * (fail closed) rather than persist a row that can never authorize a call.
     */
    fun set(
        sourceRef: String,
        toolName: String,
        scope: ToolApprovalPreferenceScope,
        scopeRef: String,
        preference: ToolApprovalPreference,
        contractHash: String?,
        nowEpochMillis: Long,
    ): String {
        require(preference != ToolApprovalPreference.ALLOW || contractHash != null) {
            "an ALLOW preference must be bound to a contractHash"
        }
        return repository.set(sourceRef, toolName, scope, scopeRef, preference, contractHash, nowEpochMillis)
    }

    /** Removes one tool's preference in one scope — a reset to the unset default, not a fourth state (point 5). */
    fun remove(
        sourceRef: String,
        toolName: String,
        scope: ToolApprovalPreferenceScope,
        scopeRef: String,
    ): Unit = repository.remove(sourceRef, toolName, scope, scopeRef)
}

package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.ToolApprovalPreferenceSource
import com.helix.core.policy.ToolApprovalResolver
import com.helix.core.policy.ToolBaseline
import com.helix.core.storage.repository.ToolApprovalPreferenceRepository
import com.helix.core.storage.repository.ToolBaselineIdentity
import com.helix.core.storage.repository.ToolRegistrationBaselineRepository

/**
 * The user's standing tool-approval preferences (HXA-200, ADR-0052) — the app's single
 * application service over the durable [ToolApprovalPreferenceRepository] and the trusted
 * tool-registration/upgrade [baselineRepository] (Gap 2).
 *
 * Write path: only this service sets and removes preferences ([set]/[remove]); the model, Skill,
 * MCP and A2A never reach the store. The future settings screen and the approval card call it
 * (HXA-201); the device tests drive it so they exercise the production write path. [reconcile] is
 * likewise the ONLY write path to the new-tool baseline — the app's trusted startup/upgrade path
 * calls it; the model/Skill/MCP/A2A/UI never do.
 *
 * Read path: it implements [ToolApprovalPreferenceSource] — the live read seam the ToolDispatcher
 * (pre-start re-resolution) and the Registry model-exposure filter both parse, so a preference
 * changed while a call is queued is resolved against the same current store by every surface
 * (ADR-0052 point 7). [effectiveFor] collapses the applicable records with the ONE resolver the
 * dispatcher uses, so exposure and execution can never disagree on the effective value; it also
 * folds in the trusted new-tool default ([ToolBaseline]) so an upgrade-introduced, unconfigured
 * tool resolves to an ASK tagged [com.helix.core.policy.ToolApprovalReason.NEW_DEFAULT].
 *
 * [currentVersionCode] is the app's own versionCode (injected at the container for testability).
 * The new-tool decision is a pure function of that plus the persisted founding/first-seen codes,
 * so it is stable across restarts of the same build and ages out on the next build.
 */
class ToolApprovalPreferenceService(
    private val repository: ToolApprovalPreferenceRepository,
    private val baselineRepository: ToolRegistrationBaselineRepository,
    private val currentVersionCode: Long,
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
            newToolDefault = newToolDefault(sourceRef, toolName),
        )

    /**
     * The trusted new-tool default input for [effectiveFor] (HXA-200 Gap 2, point 1). True only when
     * the trusted registration/upgrade baseline marks this tool as newly added in the current build
     * ([ToolBaseline.isNewDefault] over the persisted versionCode facts) AND the user has never
     * configured it. A real stored choice in ANY scope means "configured," so it always overrides the
     * default — never inferred from an empty record or a model claim.
     */
    private fun newToolDefault(
        sourceRef: String,
        toolName: String,
    ): Boolean =
        ToolBaseline.isNewDefault(
            currentVersionCode = currentVersionCode,
            foundingVersionCode = baselineRepository.foundingVersionCode(),
            firstSeenVersionCode = baselineRepository.firstSeenVersionCode(sourceRef, toolName),
        ) && !repository.hasAnyPreference(sourceRef, toolName)

    /**
     * Trusted registration of the built-in tool identities under the current build (HXA-200 Gap 2).
     * Idempotent and first-write-wins (see [ToolRegistrationBaselineRepository.reconcile]): the
     * founding anchor is set once on the very first run and each tool marker is stamped once, so a
     * restart of the same build is stable and an upgrade surfaces only the tools it actually
     * introduced. The ONLY write path to the baseline — the app's trusted startup/upgrade path
     * calls it; the model/Skill/MCP/A2A/UI never do.
     */
    fun reconcile(
        registeredIdentities: List<ToolBaselineIdentity>,
        nowEpoch: Long,
    ): Int = baselineRepository.reconcile(currentVersionCode, registeredIdentities, nowEpoch)

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

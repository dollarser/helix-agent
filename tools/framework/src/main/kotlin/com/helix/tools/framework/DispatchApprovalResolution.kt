package com.helix.tools.framework

import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.PolicyDecision
import com.helix.core.policy.ToolApprovalPreferenceSource
import com.helix.core.policy.ToolApprovalResolution
import com.helix.core.policy.ToolApprovalResolver
import com.helix.core.policy.ToolPreferenceSnapshot
import com.helix.core.policy.WorkspaceScope

/** One live preference read supplies both the decision and its immutable audit evidence. */
internal fun resolveToolApproval(
    request: ToolDispatchRequest,
    descriptor: ToolDescriptor,
    decision: PolicyDecision,
    source: ToolApprovalPreferenceSource?,
): ResolvedToolApproval {
    val snapshot =
        source?.snapshotFor(
            sourceRef = descriptor.origin.canonicalOf(),
            toolName = descriptor.name.value,
            contractHash = descriptor.contractHash.hex,
            sessionId = request.sessionId,
            workspaceRef = (request.scope as? WorkspaceScope)?.workspaceId,
        ) ?: ToolPreferenceSnapshot(EffectiveToolPreference.Unset)
    val resolution = ToolApprovalResolver.resolve(snapshot.effective, decision)
    return ResolvedToolApproval(
        resolution,
        PreferenceDecisionAudit(
            snapshot,
            descriptor.origin.canonicalOf(),
            descriptor.contractHash.hex,
            decision,
            resolution,
        ),
    )
}

internal data class ResolvedToolApproval(
    val resolution: ToolApprovalResolution,
    val audit: PreferenceDecisionAudit,
)

/** Typed internal evidence; the storage codec hashes source/scope references and allowlists fields. */
data class PreferenceDecisionAudit(
    val preference: ToolPreferenceSnapshot,
    val sourceRef: String,
    val contractHash: String,
    val policy: PolicyDecision,
    val resolution: ToolApprovalResolution,
)

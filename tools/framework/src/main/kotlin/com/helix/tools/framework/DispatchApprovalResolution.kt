package com.helix.tools.framework

import com.helix.core.policy.PolicyDecision
import com.helix.core.policy.ToolApprovalBlockCode
import com.helix.core.policy.ToolApprovalPreferenceSource
import com.helix.core.policy.ToolApprovalReason
import com.helix.core.policy.ToolApprovalResolution
import com.helix.core.policy.ToolApprovalResolver
import com.helix.core.policy.WorkspaceScope

/**
 * HXA-200 (ADR-0052 point 7): fold the live preference into the policy decision. When no
 * preference seam is wired (`source == null` — the framework default for every caller that
 * predates the feature), the policy decision maps 1:1 to its historical outcome so nothing
 * changes for them: an Allow proceeds card-free, a denial stays a policy denial, and
 * RequiresApproval still presents a card. Once a source IS wired, the ONE shared
 * [ToolApprovalResolver] applies the live preference: an unset tool keeps its original policy
 * handling (card-free for an in-scope low-risk Allow), an explicit ASK — or an ALLOW a contract
 * change invalidated — forces a card, and a DENY blocks (point 1, as clarified 2026-09-14).
 */
internal fun resolveToolApproval(
    request: ToolDispatchRequest,
    descriptor: ToolDescriptor,
    decision: PolicyDecision,
    source: ToolApprovalPreferenceSource?,
): ToolApprovalResolution =
    if (source == null) {
        when (decision) {
            is PolicyDecision.Deny -> {
                ToolApprovalResolution.Blocked(
                    ToolApprovalBlockCode.POLICY_DENIED,
                    decision.detail,
                    ToolApprovalReason.POLICY,
                )
            }

            PolicyDecision.Allow -> {
                ToolApprovalResolution.AutoProceed(reason = ToolApprovalReason.UNSET)
            }

            is PolicyDecision.RequiresApproval -> {
                ToolApprovalResolution.RequiresCard(detail = decision.detail, reason = ToolApprovalReason.POLICY)
            }
        }
    } else {
        ToolApprovalResolver.resolve(
            source.effectiveFor(
                sourceRef = descriptor.origin.canonicalOf(),
                toolName = descriptor.name.value,
                contractHash = descriptor.contractHash.hex,
                sessionId = request.sessionId,
                workspaceRef = (request.scope as? WorkspaceScope)?.workspaceId,
            ),
            decision,
        )
    }

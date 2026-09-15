package com.helix.core.policy

/**
 * The read seam for a user's stored tool-approval preferences (HXA-200, ADR-0052).
 *
 * Both the Registry exposure path (does the model see the tool at all?) and the ToolDispatcher
 * pre-start re-resolution (does this queued call proceed, card, or block?) read through this ONE
 * contract (ADR-0052 point 7: a preference changed while a call is queued is re-read before the
 * call starts), so the two surfaces can never resolve the same tool against a stale value.
 *
 * The single production implementation is the app's preference service backed by the Room
 * repository. It is a READ seam: the model, Skill, MCP and A2A can only ever read the effective
 * preference here — they cannot write it. The only write path is the user's app service
 * (settings / approval card) through the repository.
 */
fun interface ToolApprovalPreferenceSource {
    /**
     * The collapsed effective preference for [toolName] from source [sourceRef] in the context
     * ([sessionId], [workspaceRef]), with the provenance that produced it
     * ([ToolApprovalResolver.effectivePreference]).
     *
     * Returns [EffectiveToolPreference.Unset] when nothing is stored live (the call keeps its
     * original policy handling — it is NOT forced to ASK); [EffectiveToolPreference.Allow] for a
     * live, contract-matching ALLOW; an [EffectiveToolPreference.Ask] tagged
     * [ToolApprovalReason.ALLOW_INVALIDATED] when a stored ALLOW no longer matches [contractHash]
     * (a contract or source change falls an ALLOW back to ASK, point 6) while a stored ASK or DENY
     * stays live; and [EffectiveToolPreference.Deny] for a stored DENY in any applicable scope.
     *
     * @param sourceRef the trusted tool source identity (the origin's canonical form), never a
     *   display name — the key a preference is stored under.
     * @param toolName the stable tool name.
     * @param contractHash the current descriptor `contractHash`, or null when the caller has no
     *   descriptor (a stored ALLOW then never matches and falls back to the ASK).
     * @param sessionId the current session id (the SESSION scope), or null when none.
     * @param workspaceRef the current workspace id (the WORKSPACE scope), or null when none.
     */
    fun effectiveFor(
        sourceRef: String,
        toolName: String,
        contractHash: String?,
        sessionId: String?,
        workspaceRef: String?,
    ): EffectiveToolPreference
}

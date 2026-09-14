package com.helix.core.policy

import com.helix.core.model.ToolApprovalPreference

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
     * The effective stored preference for [toolName] from source [sourceRef] in the context
     * ([sessionId], [workspaceRef]), or null when nothing live remains (the caller treats null
     * as the unset default, ASK — see [ToolApprovalResolver.DEFAULT_PREFERENCE]).
     *
     * A stored [ToolApprovalPreference.ALLOW] is bound to the descriptor `contractHash`; when
     * [contractHash] no longer matches the stored binding the ALLOW is dropped back to unset
     * (a contract or source change) while a stored ASK or DENY stays live (point 6).
     *
     * @param sourceRef the trusted tool source identity (the origin's canonical form), never a
     *   display name — the key a preference is stored under.
     * @param toolName the stable tool name.
     * @param contractHash the current descriptor `contractHash`, or null when the caller has no
     *   descriptor (a stored ALLOW then never matches and is treated as unset).
     * @param sessionId the current session id (the SESSION scope), or null when none.
     * @param workspaceRef the current workspace id (the WORKSPACE scope), or null when none.
     */
    fun effectiveFor(
        sourceRef: String,
        toolName: String,
        contractHash: String?,
        sessionId: String?,
        workspaceRef: String?,
    ): ToolApprovalPreference?
}

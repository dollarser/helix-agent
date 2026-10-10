package com.helix.extensions.plugin

import com.helix.core.model.TurnState

/** Trusted native plugin UI context, obtained from a dispatched call, never from model arguments. */
data class PluginTaskIdentity(
    val conversationId: String,
    val turnId: String,
)

/** Request-time trusted display facts; a user decision still re-enters the host broker. */
data class PluginTaskApproval(
    val id: String,
    val title: String,
    val summary: String,
    val details: String,
)

data class PluginTaskSnapshot(
    val state: TurnState,
    val awaitingApproval: Boolean = false,
    val approvals: List<PluginTaskApproval> = emptyList(),
) {
    val active: Boolean
        get() = !state.isTerminal && state != TurnState.NEEDS_REVIEW && state != TurnState.INTERRUPTED
}

/** User-operated native surfaces only. No tool execution, automatic permission grant, or storage access. */
interface PluginTaskHost {
    /** Presentation only: any resumed Helix conversation already supplies task controls. */
    val conversationVisible: Boolean get() = false

    /** Non-blocking published task projection; absence means unavailable, not success. */
    fun snapshot(task: PluginTaskIdentity): PluginTaskSnapshot?

    /** Stop only this original task; already-issued effects still require normal settlement. */
    fun requestStop(task: PluginTaskIdentity)

    /** Explicit human decision on one original pending request, never an extension/tool permission API. */
    fun reviewApproval(
        task: PluginTaskIdentity,
        approvalId: String,
        approve: Boolean,
    )

    fun openConversation(task: PluginTaskIdentity)
}

/** Optional trusted native presentation notified by the host's approval sink, never by tool arguments. */
interface PluginApprovalPresentation {
    fun showApproval(task: PluginTaskIdentity)
}

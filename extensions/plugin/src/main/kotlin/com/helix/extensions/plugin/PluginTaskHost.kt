package com.helix.extensions.plugin

import com.helix.core.model.TurnState

/** Trusted native plugin UI context, obtained from a dispatched call, never from model arguments. */
data class PluginTaskIdentity(
    val conversationId: String,
    val turnId: String,
)

data class PluginTaskSnapshot(
    val state: TurnState,
    val awaitingApproval: Boolean = false,
) {
    val active: Boolean
        get() = !state.isTerminal && state != TurnState.NEEDS_REVIEW && state != TurnState.INTERRUPTED
}

/** User-operated native surfaces only. No tool execution, permission grant, or storage access. */
interface PluginTaskHost {
    /** Non-blocking published task projection; absence means unavailable, not success. */
    fun snapshot(task: PluginTaskIdentity): PluginTaskSnapshot?

    /** Stop only this original task; already-issued effects still require normal settlement. */
    fun requestStop(task: PluginTaskIdentity)

    fun openConversation(task: PluginTaskIdentity)
}

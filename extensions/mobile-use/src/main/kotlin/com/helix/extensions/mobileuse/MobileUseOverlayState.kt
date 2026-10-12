package com.helix.extensions.mobileuse

import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.extensions.plugin.PluginTaskSnapshot

/** Process-local display/control fence. Durable cancellation remains owned by the host. */
internal class MobileUseOverlayState(
    private val restoreDelayMillis: Long = 0,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile
    var owner: PluginTaskIdentity? = null
        private set
    private val stopped = mutableSetOf<PluginTaskIdentity>()
    private val hidden = mutableSetOf<Any>()
    private var closed = false
    private var restoreAt: Long? = null

    @Synchronized
    fun bind(task: PluginTaskIdentity): Boolean {
        if (closed || task in stopped) return false
        if (owner != task) restoreAt = null
        owner = task
        return true
    }

    @Synchronized
    fun takeOver(expected: PluginTaskIdentity): Boolean {
        if (closed || owner != expected || expected in stopped) return false
        stopped.add(expected)
        return true
    }

    @Synchronized
    fun allowed(): Boolean = !closed && owner !in stopped

    @Synchronized
    fun visible(
        task: PluginTaskIdentity,
        snapshot: PluginTaskSnapshot?,
        conversationVisible: Boolean = false,
    ): Boolean =
        !conversationVisible && !closed && hidden.isEmpty() && owner == task && snapshot?.active == true &&
            (restoreAt?.let { now() >= it } != false || snapshot.awaitingApproval)

    @Synchronized
    fun canNavigate(expected: PluginTaskIdentity): Boolean = !closed && owner == expected

    @Synchronized
    fun canReview(
        task: PluginTaskIdentity,
        snapshot: PluginTaskSnapshot?,
        approvalId: String,
    ): Boolean =
        visible(task, snapshot) && task !in stopped && snapshot?.state != com.helix.core.model.TurnState.CANCELLING &&
            snapshot?.approvals?.any { it.id == approvalId } == true

    @Synchronized
    fun suppress(): Any = Any().also { hidden.add(it) }

    @Synchronized
    fun release(ticket: Any) {
        if (hidden.remove(ticket) && hidden.isEmpty()) restoreAt = now() + restoreDelayMillis
    }

    @Synchronized
    fun hide() {
        owner = null
    }

    @Synchronized
    fun close() {
        closed = true
        owner = null
        hidden.clear()
        stopped.clear()
    }
}

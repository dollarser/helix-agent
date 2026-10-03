package com.helix.extensions.mobileuse

import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.extensions.plugin.PluginTaskSnapshot

/** Process-local display/control fence. Durable cancellation remains owned by the host. */
internal class MobileUseOverlayState {
    @Volatile
    var owner: PluginTaskIdentity? = null
        private set
    private val stopped = mutableSetOf<PluginTaskIdentity>()
    private val hidden = mutableSetOf<Any>()
    private var closed = false

    @Synchronized
    fun bind(task: PluginTaskIdentity): Boolean {
        if (closed || task in stopped) return false
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
    ): Boolean = !closed && hidden.isEmpty() && owner == task && snapshot?.active == true

    @Synchronized
    fun suppress(): Any = Any().also { hidden.add(it) }

    @Synchronized
    fun release(ticket: Any) {
        hidden.remove(ticket)
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

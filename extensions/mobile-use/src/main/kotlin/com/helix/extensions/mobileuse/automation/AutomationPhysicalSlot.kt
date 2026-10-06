package com.helix.extensions.mobileuse.automation

import java.util.concurrent.atomic.AtomicReference

/** A late callback can release only the physical operation it originally owned. */
internal class AutomationPhysicalSlot {
    private val owner = AtomicReference<Any?>(null)

    fun isOccupied(): Boolean = owner.get() != null

    fun acquire(): Any? = Any().takeIf { owner.compareAndSet(null, it) }

    fun release(ticket: Any) {
        owner.compareAndSet(ticket, null)
    }
}

package com.helix.extensions.mobileuse.automation

import java.util.concurrent.atomic.AtomicReference

/** A late callback can release only the physical operation it originally owned. */
internal class AutomationPhysicalSlot {
    private val owner = AtomicReference<Any?>(null)

    fun isOccupied(): Boolean = owner.get() != null

    fun acquire(retained: AutoCloseable? = null): Any? {
        val ticket = Ticket(retained)
        if (owner.compareAndSet(null, ticket)) return ticket
        retained?.close()
        return null
    }

    fun attach(
        ticket: Any,
        retained: AutoCloseable,
    ) {
        (ticket as Ticket).attach(retained)
    }

    fun release(ticket: Any) {
        if (owner.compareAndSet(ticket, null)) (ticket as Ticket).close()
    }

    private class Ticket(
        private val resource: AutoCloseable?,
    ) : AutoCloseable {
        private var hidden: AutoCloseable? = null
        private var closed = false

        @Synchronized fun attach(retained: AutoCloseable) {
            check(hidden == null)
            if (closed) retained.close() else hidden = retained
        }

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            try {
                hidden?.close()
            } finally {
                resource?.close()
            }
        }
    }
}

package com.helix.app.terminal

import com.helix.tools.framework.ExecutionOwnership

/** Durable control identities are history, never physical execution slots. */
internal class ManualTerminalBindings(
    private val stores: () -> List<ExecutionOwnership.Store>,
    private val allocate: () -> ExecutionOwnership.Store,
) {
    fun owners(): List<Pair<ExecutionOwnership.Store, ExecutionOwnership.Owner>> {
        val seen = mutableSetOf<ExecutionOwnership.Owner>()
        return stores().mapNotNull { store ->
            store.read()?.let { owner ->
                if (seen.add(owner)) {
                    store to owner
                } else {
                    check(store.compareAndSet(owner, null)) { "Duplicate terminal binding changed" }
                    null
                }
            }
        }
    }

    fun find(sessionId: String?): Pair<ExecutionOwnership.Store, ExecutionOwnership.Owner> =
        checkNotNull(owners().firstOrNull { sessionId == null || it.second.executionId == sessionId }) {
            "Terminal identity not found"
        }

    fun fresh(): ExecutionOwnership.Store =
        allocate().also {
            check(it.read() == null) { "New terminal identity already exists" }
        }
}

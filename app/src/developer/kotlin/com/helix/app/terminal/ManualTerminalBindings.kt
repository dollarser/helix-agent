package com.helix.app.terminal

import com.helix.tools.framework.ExecutionOwnership

/** Bounded host identities, including older slots on a device whose current CPU allowance is lower. */
internal class ManualTerminalBindings(
    private val stores: List<ExecutionOwnership.Store>,
) {
    fun owners(): List<Pair<ExecutionOwnership.Store, ExecutionOwnership.Owner>> {
        val seen = mutableSetOf<ExecutionOwnership.Owner>()
        return stores.mapNotNull { store ->
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

    fun free(limit: Int): ExecutionOwnership.Store {
        check(owners().size < limit) { "CAPACITY_FULL" }
        return checkNotNull(stores.take(limit).firstOrNull { it.read() == null }) { "CAPACITY_FULL" }
    }
}

package com.helix.app.terminal

import com.helix.app.internal.LineStore
import com.helix.tools.framework.ExecutionOwnership

/** A missing Runtime journal is not exit proof. A later OS boot is; retain the observation/receipt. */
internal class TerminalBootRecovery(
    private val store: LineStore,
) {
    fun observe(
        owner: ExecutionOwnership.Owner,
        boot: Int?,
    ): Boolean {
        if (boot == null || boot < 0) return false
        val lines = store.lines(key(owner))
        return if (lines.isEmpty()) {
            store.setLines(key(owner), listOf(owner.executionId, owner.generation, boot.toString()))
            false
        } else {
            val observed = lines.getOrNull(2)?.toIntOrNull()
            lines.size in 3..4 && lines.take(2) == listOf(owner.executionId, owner.generation) &&
                observed != null && observed >= 0 && boot > observed
        }
    }

    fun settle(
        owner: ExecutionOwnership.Owner,
        boot: Int?,
        ownership: ExecutionOwnership,
        binding: ExecutionOwnership.Store,
    ) {
        check(binding.read() == owner) { "Terminal binding changed" }
        check(observe(owner, boot)) { "Missing terminal has no reboot proof" }
        // Persist a truthful recovery receipt before releasing the exact host identity.
        // The command outcome remains UNKNOWN; no Runtime ACK or process-tree proof is invented.
        store.setLines(key(owner), store.lines(key(owner)).take(3) + requireNotNull(boot).toString())
        if (ownership.isRetained(owner)) {
            checkNotNull(ownership.acquireReconciliation(owner)).use { check(it.settle()) }
        }
        check(binding.compareAndSet(owner, null)) { "Terminal binding changed" }
    }

    private fun key(owner: ExecutionOwnership.Owner) = "missing-terminal-${owner.executionId}-${owner.generation}"
}

package com.helix.app.terminal

import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.tools.framework.ExecutionOwnership

/** ACK the original proven exit before releasing either durable host identity. */
internal fun settleTerminalBinding(
    ownership: ExecutionOwnership,
    binding: ExecutionOwnership.Store,
    owner: ExecutionOwnership.Owner,
    query: () -> PtySessionRecord?,
    acknowledge: () -> PtySessionRecord?,
) {
    val observed = checkNotNull(query()) { "Execution is unknown" }
    check(observed.origin.sessionId == owner.executionId && observed.origin.generation == owner.generation)
    check(observed.stopProof != null) { "Execution not proven stopped" }
    val ack = checkNotNull(acknowledge())
    check(ack.origin == observed.origin && ack.reconciled) { "Terminal acknowledgement identity changed" }
    if (ownership.isRetained(owner)) {
        checkNotNull(ownership.acquireReconciliation(owner)) { "This terminal is being settled" }
            .use { check(it.settle()) { "Terminal identity changed" } }
    }
    check(binding.compareAndSet(owner, null)) { "Terminal binding changed" }
}

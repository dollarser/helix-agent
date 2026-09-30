package com.helix.app.chat

import com.helix.tools.framework.ExecutionOwnership
import java.util.UUID

/** Write-ahead physical-execution identity, not a second task journal or a rollback claim. */
internal class NativeJavascriptOwnership(
    private val ownership: ExecutionOwnership,
) {
    fun <T> execute(
        callId: String,
        executionId: String,
        action: () -> T,
    ): T {
        val owner = ExecutionOwnership.Owner(PREFIX + executionId, UUID.randomUUID().toString())
        check(ownership.retainForCall(callId, owner)) { "Cannot retain native execution ownership" }
        // A returned client result proves no submit OR original process death.
        // An exception retains ownership.
        val result = action()
        check(ownership.releaseStoppedForCall(callId, owner)) { "Native execution owner changed" }
        return result
    }

    fun interruptedOwner(): ExecutionOwnership.Owner? =
        ownership.retainedOwner()?.takeIf {
            it.executionId.startsWith(PREFIX)
        }

    /** A single native service component is retired, never EXECUTEd, before reopening effect admission. */
    fun recover(
        owner: ExecutionOwnership.Owner,
        retire: (String) -> Boolean,
    ): Boolean {
        require(owner.executionId.startsWith(PREFIX))
        val permit = ownership.acquireReconciliation(owner) ?: return false
        return permit.use {
            if (retire(owner.executionId.removePrefix(PREFIX))) it.settle() else false
        }
    }

    private companion object {
        const val PREFIX = "quickjs-native:"
    }
}

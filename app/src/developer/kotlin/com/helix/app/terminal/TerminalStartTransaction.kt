package com.helix.app.terminal

import com.helix.tools.framework.ExecutionOwnership

/** Pre-submit failures can release their own reservation; a started or uncertain IPC cannot. */
internal class TerminalStartTransaction(
    private val ownership: ExecutionOwnership,
    private val binding: ExecutionOwnership.Store,
) {
    fun <T> launch(
        callId: String,
        owner: ExecutionOwnership.Owner,
        primary: Boolean,
        submit: (() -> Unit) -> T,
        refused: (T) -> Boolean,
    ): T {
        val permit = if (primary) checkNotNull(ownership.acquire(callId)) { "Execution is busy" } else null
        try {
            return reserved(callId, owner, primary, submit, refused)
        } finally {
            permit?.close()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup failure must be attached to the original failure.
    private fun rollbackUnsubmitted(
        callId: String,
        owner: ExecutionOwnership.Owner,
        primary: Boolean,
        failure: Throwable,
    ) {
        try {
            // CAS may persist then throw: inspect exact identity before safe pre-submit cleanup.
            if (primary && ownership.retainedOwner() == owner) {
                check(ownership.releaseUnsubmittedForCall(callId, owner))
            }
            if (binding.read() == owner) check(binding.compareAndSet(owner, null))
        } catch (cleanup: Throwable) {
            failure.addSuppressed(cleanup)
        }
    }

    // Preserve failures and cleanup causes; never report a failed start as success.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> reserved(
        callId: String,
        owner: ExecutionOwnership.Owner,
        primary: Boolean,
        submit: (() -> Unit) -> T,
        refused: (T) -> Boolean,
    ): T {
        var submitted = false
        try {
            check(binding.compareAndSet(null, owner)) { "Terminal binding changed" }
            if (primary) {
                check(ownership.retainForCall(callId, owner)) { "Terminal ownership transfer refused" }
            }
            val result = submit { submitted = true }
            check(submitted) { "Terminal submission boundary was not recorded" }
            if (refused(result)) {
                submitted = false // A definitive refusal, not a timeout or missing reply.
                error("Manual terminal was not started; check Runtime readiness")
            }
            return result
        } catch (failure: Throwable) {
            if (!submitted) rollbackUnsubmitted(callId, owner, primary, failure)
            throw failure
        }
    }
}

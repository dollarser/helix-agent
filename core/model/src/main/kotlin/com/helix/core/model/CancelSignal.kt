package com.helix.core.model

/**
 * Cooperative cancellation for one dispatch (roadmap HXA-035 pipeline step "timeout/cancel").
 * The dispatcher checks the signal at stage boundaries and before starting execution; the
 * executor is expected to poll it while running and report cancellation.
 * Cancellation is cooperative by design: the framework never kills a mid-effect call and
 * never blindly replays one (doc 11).
 */
interface CancelSignal {
    fun isCancelled(): Boolean
}

/** The default signal: never cancelled. */
data object NoCancellation : CancelSignal {
    override fun isCancelled(): Boolean = false
}

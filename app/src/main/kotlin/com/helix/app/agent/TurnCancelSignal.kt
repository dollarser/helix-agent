package com.helix.app.agent

import com.helix.tools.framework.CancelSignal

/** The turn's [CancelSignal]: the stop button flips it; the dispatcher checks it at its stage checks. */
internal class TurnCancelSignal(
    private val deadlineExpired: () -> Boolean = { false },
) : CancelSignal {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    override fun isCancelled(): Boolean = cancelled || deadlineExpired()
}

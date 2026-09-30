package com.helix.app.engine

import kotlinx.coroutines.CompletableDeferred

/** Preparation failures are not a user Stop. The driver owns their durable FAILED settlement. */
internal class TurnLaunchGate {
    private val ready = CompletableDeferred<Unit>()

    @Suppress("TooGenericExceptionCaught") // Preserve preparation failure without cancelling the waiting driver.
    fun prepare(block: () -> Unit) {
        try {
            block()
            ready.complete(Unit)
        } catch (failure: Exception) {
            ready.completeExceptionally(LaunchFailure(failure))
        }
    }

    suspend fun await() = ready.await()

    private class LaunchFailure(
        cause: Exception,
    ) : Exception("Turn launch preparation failed", cause)
}

package com.helix.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Orders user configuration edits and sends before they enter the existing admission gate. */
internal class SessionActionQueue(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var tail: Job? = null

    @Suppress("TooGenericExceptionCaught") // Failure belongs to this receipt; later user actions remain available.
    fun <T> submit(action: suspend () -> T): Deferred<T> {
        val receipt = CompletableDeferred<T>()
        val worker =
            synchronized(lock) {
                val previous = tail
                scope
                    .launch(start = CoroutineStart.LAZY) {
                        previous?.join()
                        try {
                            receipt.complete(action())
                        } catch (cancel: CancellationException) {
                            receipt.cancel(cancel)
                            throw cancel
                        } catch (error: Exception) {
                            receipt.completeExceptionally(error)
                        }
                    }.also { tail = it }
            }
        worker.invokeOnCompletion { cause ->
            if (cause != null) receipt.completeExceptionally(cause)
            synchronized(lock) { if (tail === worker) tail = null }
        }
        worker.start()
        // Cancelling a caller's receipt does not cancel service-owned admission or reorder successors.
        return receipt
    }
}

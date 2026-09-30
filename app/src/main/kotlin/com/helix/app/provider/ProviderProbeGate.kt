package com.helix.app.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One service owns configuration mutation and probe publication; network work never holds this lock. */
internal class ProviderProbeGate {
    private val mutex = Mutex()
    private val generations = mutableMapOf<Pair<String, String>, Ticket>()

    private class Ticket(
        val operation: String,
    )

    suspend fun <T> begin(
        id: String,
        operation: String = "connection",
        snapshot: suspend () -> T,
    ): Pair<Any, T> =
        mutex.withLock {
            val token = Ticket(operation)
            generations[id to operation] = token
            token to snapshot()
        }

    suspend fun publish(
        id: String,
        token: Any,
        block: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            val ticket = token as? Ticket ?: return@withLock false
            if (generations[id to ticket.operation] !== token) return@withLock false
            currentCoroutineContext().ensureActive()
            // Once admitted, publish all local stores before cancellation can interrupt the commit.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { block() }
            true
        }

    suspend fun <T> mutate(
        id: String,
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            generations.keys.removeAll { it.first == id }
            block()
        }
}

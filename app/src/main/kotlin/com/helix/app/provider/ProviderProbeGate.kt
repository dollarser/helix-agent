package com.helix.app.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One service owns configuration mutation and probe publication; network work never holds this lock. */
internal class ProviderProbeGate {
    private val mutex = Mutex()
    private val generations = mutableMapOf<String, Any>()

    suspend fun <T> begin(
        id: String,
        snapshot: suspend () -> T,
    ): Pair<Any, T> =
        mutex.withLock {
            val token = Any()
            generations[id] = token
            token to snapshot()
        }

    suspend fun publish(
        id: String,
        token: Any,
        block: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            if (generations[id] !== token) return@withLock false
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
            generations.remove(id)
            block()
        }
}

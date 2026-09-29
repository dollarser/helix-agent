package com.helix.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounded original-job observation/collection. Never submits, resumes, or replays a Runtime job. */
internal class AutomaticRuntimeCollection(
    private val scope: CoroutineScope,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    enum class Observation { COMPLETE, RUNNING, RETRY, DEFERRED }

    private val requested = mutableSetOf<String>()

    @Synchronized
    fun request(
        key: String,
        collect: suspend () -> Observation,
    ) {
        if (!requested.add(key)) return
        scope.launch {
            var failures = 0
            repeat(MAX_OBSERVATIONS) { attempt ->
                if (attempt > 0) pause((attempt * RETRY_DELAY_MS).coerceAtMost(MAX_DELAY_MS))
                val observation =
                    try {
                        collect()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Observation.RETRY
                    }
                when (observation) {
                    Observation.COMPLETE -> {
                        return@launch
                    }

                    Observation.DEFERRED -> {
                        synchronized(this@AutomaticRuntimeCollection) { requested.remove(key) }
                        return@launch
                    }

                    Observation.RUNNING -> {
                        failures = 0
                    }

                    Observation.RETRY -> {
                        failures++
                        if (failures >= MAX_FAILURES) return@launch
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_FAILURES = 3
        const val MAX_OBSERVATIONS = 60
        const val RETRY_DELAY_MS = 1000L
        const val MAX_DELAY_MS = 10000L
    }
}

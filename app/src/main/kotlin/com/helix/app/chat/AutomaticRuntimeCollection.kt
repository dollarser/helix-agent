package com.helix.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounded original-job observation/collection. Never submits, resumes, or replays a Runtime job. */
internal class AutomaticRuntimeCollection(
    private val scope: CoroutineScope,
    private val nowNanos: () -> Long = System::nanoTime,
    private val onPaused: (String) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    enum class Observation { COMPLETE, RUNNING, RETRY, DEFERRED }

    private val requested = mutableSetOf<String>()
    private val settled = linkedMapOf<String, Long>()

    /** A new visible conversation may retry a paused observation, never replay an execution. */
    @Synchronized
    fun resetPaused() {
        settled.entries.removeIf { it.value != Long.MAX_VALUE }
    }

    @Synchronized
    private fun remember(
        key: String,
        until: Long,
    ) {
        settled[key] = until
        while (settled.size > MAX_REMEMBERED) settled.remove(settled.keys.first())
    }

    private fun pauseKey(
        key: String,
        reason: String,
    ) {
        remember(key, nowNanos() + COOLDOWN_NANOS)
        onPaused(reason)
    }

    @Synchronized
    fun request(
        key: String,
        collect: suspend () -> Observation,
    ) {
        val until = settled[key]
        if (until != null && (until == Long.MAX_VALUE || nowNanos() - until < 0)) return
        if (!requested.add(key)) return
        settled.remove(key)
        val job =
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
                            remember(key, Long.MAX_VALUE)
                            return@launch
                        }

                        Observation.DEFERRED -> {
                            return@launch
                        }

                        Observation.RUNNING -> {
                            failures = 0
                        }

                        Observation.RETRY -> {
                            failures++
                            if (failures >= MAX_FAILURES) {
                                pauseKey(key, "OBSERVATION_RETRY_LIMIT")
                                return@launch
                            }
                        }
                    }
                }
                pauseKey(key, "OBSERVATION_WINDOW_EXHAUSTED")
            }
        job.invokeOnCompletion {
            synchronized(this@AutomaticRuntimeCollection) { requested.remove(key) }
        }
    }

    private companion object {
        const val MAX_REMEMBERED = 512
        const val COOLDOWN_NANOS = 30_000_000_000L
        const val MAX_FAILURES = 3
        const val MAX_OBSERVATIONS = 60
        const val RETRY_DELAY_MS = 1000L
        const val MAX_DELAY_MS = 10000L
    }
}

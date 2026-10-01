package com.helix.tools.framework

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pure in-process waiting state. The timer touches memory and schedules queries; it never performs host I/O. */
internal class JobObservationWait(
    private val call: ExecutableToolCall,
    private val bindings: List<JobObservationBinding>,
    private val waiting: Boolean,
    private val condition: JobWaitCondition,
    private val limits: JobObservationLimits,
    private val startedNanos: Long,
    remainingMillis: Long,
    private val queries: JobQueryLane,
    private val mayRead: () -> Boolean,
) {
    val result = CompletableFuture<ToolExecutorResult>()
    private val stop = AtomicBoolean()
    private val pending = linkedMapOf<JobObservationBinding, JobQueryLane.Flight>()
    private val known = linkedMapOf<JobObservationBinding, SeenJobObservation>()
    private val nextPoll = mutableMapOf<JobObservationBinding, Long>()
    private val outerNanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis)
    private val waitNanos =
        TimeUnit.MILLISECONDS.toNanos(
            minOf(limits.maxWaitMillis, (remainingMillis - limits.finalizationReserveMillis).coerceAtLeast(0)),
        )
    private var scanOffset = 0

    @Synchronized
    fun tick(now: Long) {
        if (result.isDone) return
        when {
            stop.get() || call.cancel.isCancelled() -> {
                result.complete(JobObservationOutput.cancelled())
            }

            now - startedNanos >= outerNanos -> {
                result.complete(JobObservationOutput.timedOut())
            }

            now - startedNanos >= waitNanos -> {
                complete(JobObservationReason.WAIT_EXPIRED, now)
            }

            else -> {
                drain(now)
                if (!result.isDone) {
                    when {
                        known.values.any { it.value.requiresReview } -> {
                            complete(JobObservationReason.REVIEW_REQUIRED, now)
                        }

                        satisfied() -> {
                            complete(
                                if (waiting) JobObservationReason.CONDITION_MET else JobObservationReason.SNAPSHOT,
                                now,
                            )
                        }

                        else -> {
                            poll(now)
                        }
                    }
                }
            }
        }
    }

    private fun satisfied(): Boolean =
        if (!waiting || condition == JobWaitCondition.ALL) {
            bindings.all { known[it]?.value?.let { value -> !waiting || value.terminal } == true }
        } else {
            known.values.any { it.value.terminal }
        }

    private fun drain(now: Long) {
        val iterator = pending.entries.iterator()
        while (iterator.hasNext() && !result.isDone) {
            val (binding, flight) = iterator.next()
            if (flight.result.isDone) {
                iterator.remove()
                nextPoll[binding] = now + TimeUnit.MILLISECONDS.toNanos(limits.pollMillis)
                accept(binding, flight, now)
            } else if (now - flight.startedNanos >= TimeUnit.MILLISECONDS.toNanos(limits.queryTimeoutMillis)) {
                // Keep the shared physical query occupied until its actual return, even after this wait ends.
                complete(JobObservationReason.SOURCE_UNAVAILABLE, now)
            }
        }
    }

    private fun accept(
        binding: JobObservationBinding,
        flight: JobQueryLane.Flight,
        now: Long,
    ) {
        try {
            when (val value = flight.result.getNow(null)) {
                is JobQueryLane.QueryResult.Value -> {
                    known[binding] = SeenJobObservation(value.observation, flight.completedNanos)
                }

                JobQueryLane.QueryResult.Revoked -> {
                    result.complete(JobObservationOutput.revoked())
                }

                else -> {
                    complete(JobObservationReason.SOURCE_UNAVAILABLE, now)
                }
            }
        } catch (failure: CompletionException) {
            result.completeExceptionally(failure.cause ?: failure)
        }
    }

    private fun poll(now: Long) {
        var busy = false
        val offset = scanOffset++ % bindings.size
        repeat(bindings.size) { index ->
            val binding = bindings[(offset + index) % bindings.size]
            if (known[binding]?.value?.terminal != true && binding !in pending && now >= (nextPoll[binding] ?: now)) {
                val flight = queries.acquire(binding, mayRead)
                if (flight == null) busy = true else pending[binding] = flight
            }
        }
        if (busy && known.isEmpty() && pending.isEmpty()) complete(JobObservationReason.OBSERVATION_BUSY, now)
    }

    private fun complete(
        reason: JobObservationReason,
        now: Long,
    ) {
        result.complete(JobObservationOutput.result(reason, bindings, known, now, limits.queryTimeoutMillis, waiting))
    }

    fun requestStop(): Boolean = !result.isDone && stop.compareAndSet(false, true)

    fun fail(cause: Throwable) {
        result.completeExceptionally(cause)
    }

    @Synchronized
    fun clear() {
        pending.clear()
        known.clear()
        nextPoll.clear()
    }
}

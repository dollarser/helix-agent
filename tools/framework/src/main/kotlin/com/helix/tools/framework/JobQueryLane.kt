package com.helix.tools.framework

import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/** One outstanding physical query per exact execution; abandoning an observer never frees this slot. */
internal class JobQueryLane(
    private val port: JobObservationPort,
    private val limit: Int,
    private val nanoTime: () -> Long,
) : AutoCloseable {
    private val lock = Any()
    private val flights = mutableMapOf<JobObservationBinding, Flight>()
    private val pool = ToolExecutionPools.bounded(limit, "job-query")
    private var closed = false

    class Flight(
        val startedNanos: Long,
    ) {
        val result = CompletableFuture<QueryResult>()

        @Volatile var completedNanos: Long = startedNanos
    }

    sealed interface QueryResult {
        data class Value(
            val observation: JobObservation,
        ) : QueryResult

        data object Unavailable : QueryResult

        data object Revoked : QueryResult
    }

    fun acquire(
        binding: JobObservationBinding,
        mayRead: () -> Boolean,
    ): Flight? =
        synchronized(lock) {
            flights[binding]?.let { return@synchronized it }
            if (closed || flights.size >= limit) return@synchronized null
            val flight = Flight(nanoTime())
            flights[binding] = flight
            try {
                pool.execute { perform(binding, flight, mayRead) }
                flight
            } catch (_: RejectedExecutionException) {
                flights.remove(binding, flight)
                null
            }
        }

    /** All I/O and live permission checks run here, never on the observation timer. */
    @Suppress("TooGenericExceptionCaught") // Adapter faults are unavailable observations, not failed executions.
    private fun perform(binding: JobObservationBinding, flight: Flight, mayRead: () -> Boolean) {
        try {
            val result = read(binding, mayRead)
            flight.completedNanos = nanoTime()
            flight.result.complete(result)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            flight.result.complete(QueryResult.Unavailable)
        } catch (_: Exception) {
            flight.result.complete(QueryResult.Unavailable)
        } catch (failure: Error) {
            flight.result.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(lock) { flights.remove(binding, flight) }
            Thread.interrupted()
        }
    }

    private fun read(
        binding: JobObservationBinding,
        mayRead: () -> Boolean,
    ): QueryResult =
        if (!mayRead()) {
            // Shared query results belong to the execution, not to the initiating observer.
            // Every subscriber revalidates its own permission at publication.
            QueryResult.Unavailable
        } else if (!port.isCurrent(binding)) {
            QueryResult.Revoked
        } else {
            val value = port.query(binding)
            when {
                !port.isCurrent(binding) -> QueryResult.Revoked
                value == null || value.binding != binding -> QueryResult.Unavailable
                else -> QueryResult.Value(value)
            }
        }

    fun outstanding(): Int = synchronized(lock) { flights.size }

    override fun close() {
        synchronized(lock) {
            closed = true
            // No interrupt/cancel-based claim that Binder has exited. Running slots stay accounted for.
            pool.shutdown()
        }
    }
}

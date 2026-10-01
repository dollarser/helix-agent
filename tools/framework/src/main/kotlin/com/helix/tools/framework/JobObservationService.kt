package com.helix.tools.framework

import com.helix.core.model.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Application-scoped observers; neither this service nor its timer is an owner of the observed Job. */
@Suppress("TooManyFunctions") // Registration, scoped stops and shutdown share one observer-quota owner.
class JobObservationService(
    private val port: JobObservationPort,
    private val clock: Clock,
    private val limits: JobObservationLimits = JobObservationLimits(),
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private val lock = Any()
    private val queries = JobQueryLane(port, limits.maxQueries, nanoTime)
    private val active = linkedMapOf<String, Registration>()
    private val timer =
        ScheduledThreadPoolExecutor(1) { action ->
            Thread(action, "job-observation-timer").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(60, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    private var ticker: ScheduledFuture<*>? = null
    private var closed = false

    private data class Registration(
        val sessionId: String,
        val handles: Set<String>,
        val wait: JobObservationWait,
    )

    fun statusExecutor(): ToolExecutor = JobObservationExecutor(this, waiting = false)

    fun awaitExecutor(): ToolExecutor = JobObservationExecutor(this, waiting = true)

    /** A user stop-wait command does not call the Runtime's cancellation endpoint. */
    fun stopWaiting(
        sessionId: String,
        callId: String,
    ): Boolean {
        val registration = synchronized(lock) { active[callId]?.takeIf { it.sessionId == sessionId } }
        return registration?.wait?.requestStop() == true
    }

    /** Explicit local user read, sharing the exact query lane without creating a model ToolCall. */
    fun statusForUser(call: ExecutableToolCall): CompletableFuture<ToolExecutorResult> =
        JobObservationPublication.settle(start(call, false) { !call.cancel.isCancelled() }) { result, failure ->
            if (failure != null) throw java.util.concurrent.CompletionException(failure)
            requireNotNull(result)
        }

    /** Stops only matching observers; no Runtime cancellation or output import is involved. */
    fun stopWaitingForJob(
        sessionId: String,
        handle: String,
    ): Boolean {
        val matching =
            synchronized(lock) {
                active.values.filter { it.sessionId == sessionId && handle in it.handles }
            }
        return matching.map { it.wait.requestStop() }.any { it }
    }

    internal fun start(
        call: ExecutableToolCall,
        waiting: Boolean,
        mayRead: () -> Boolean,
    ): JobObservationSubmission =
        try {
            require(!call.sessionId.isNullOrBlank() && !call.turnId.isNullOrBlank()) { "JOB_OWNER_REQUIRED" }
            val sessionId = requireNotNull(call.sessionId)
            val handles = handles(call, waiting)
            val bindings =
                handles.map { handle ->
                    port.resolve(sessionId, handle).also {
                        require(it.sessionId == sessionId && it.handle == handle) { "JOB_HANDLE_MISMATCH" }
                    }
                }
            // Resolve and validate the whole set before any query, including duplicates supplied by the model.
            require(bindings.all(port::isCurrent)) { "JOB_BINDING_CHANGED" }
            val condition =
                when ((call.args["condition"] as? JsonPrimitive)?.content) {
                    null, "ANY" -> JobWaitCondition.ANY
                    "ALL" -> JobWaitCondition.ALL
                    else -> throw IllegalArgumentException("JOB_WAIT_CONDITION")
                }
            val remaining = Duration.between(clock.now(), call.deadline).toMillis().coerceIn(0, MAX_OUTER_MILLIS)
            register(call, bindings.distinct(), waiting, condition, remaining, mayRead)
        } catch (_: IllegalArgumentException) {
            JobObservationSubmission.immediate(
                ToolExecutorResult.Failed(
                    "JOB_HANDLE_INVALID: no query started; use an original handle from this session.",
                    sideEffectFree = true,
                ),
            )
        }

    private fun handles(
        call: ExecutableToolCall,
        waiting: Boolean,
    ): List<String> {
        val values =
            if (waiting) {
                (call.args["handles"] as? JsonArray)?.toList() ?: throw IllegalArgumentException("JOB_HANDLES_REQUIRED")
            } else {
                listOf(requireNotNull(call.args["originalCallId"]))
            }
        require(values.size in 1..limits.maxHandles)
        return values.map { value ->
            require(value is JsonPrimitive && value.isString)
            value.content.also { require(it.isNotBlank() && it.length <= 128 && it.none { char -> char.code < 32 }) }
        }
    }

    private fun register(
        call: ExecutableToolCall,
        bindings: List<JobObservationBinding>,
        waiting: Boolean,
        condition: JobWaitCondition,
        remaining: Long,
        mayRead: () -> Boolean,
    ): JobObservationSubmission =
        synchronized(lock) {
            if (closed || active.size >= limits.maxObservers ||
                active.values.count { it.sessionId == call.sessionId } >= limits.maxPerSession
            ) {
                return@synchronized JobObservationSubmission.immediate(JobObservationOutput.busy())
            }
            check(call.toolCallId !in active) { "JOB_OBSERVER_ALREADY_ACTIVE" }
            val wait =
                JobObservationWait(
                    call,
                    bindings,
                    waiting,
                    condition,
                    limits,
                    nanoTime(),
                    remaining,
                    queries,
                    mayRead,
                )
            val registration =
                Registration(
                    requireNotNull(call.sessionId),
                    bindings.map { it.handle }.toSet(),
                    wait,
                )
            active[call.toolCallId] = registration
            if (ticker == null) {
                ticker = timer.scheduleAtFixedRate(::tick, 0, limits.tickMillis, TimeUnit.MILLISECONDS)
            }
            JobObservationSubmission(
                wait.result,
                beforePublish = { result ->
                    val permitted =
                        result !is ToolExecutorResult.Completed ||
                            (!call.cancel.isCancelled() && mayRead() && bindings.all(port::isCurrent))
                    if (!permitted) {
                        ToolExecutorResult.CancelledWithEffectTruth(
                            "JOB_OBSERVATION_REVOKED: no new result was disclosed; original execution is unchanged.",
                            sideEffectFree = true,
                            requiresReview = false,
                        )
                    } else {
                        result
                    }
                },
            ) { release(call.toolCallId, registration) }
        }

    // A broken callback must settle its own waiter, not disable the shared timer.
    @Suppress("TooGenericExceptionCaught")
    private fun tick() {
        val current = synchronized(lock) { active.values.toList() }
        current.forEach { registration ->
            try {
                registration.wait.tick(nanoTime())
            } catch (failure: Throwable) {
                registration.wait.fail(failure)
            }
        }
    }

    private fun release(
        callId: String,
        registration: Registration,
    ) {
        synchronized(lock) {
            active.remove(callId, registration)
            if (active.isEmpty()) {
                ticker?.cancel(false)
                ticker = null
            }
        }
        registration.wait.clear()
    }

    internal fun activeCount(): Int = synchronized(lock) { active.size }

    internal fun outstandingQueries(): Int = queries.outstanding()

    override fun close() {
        val registrations =
            synchronized(lock) {
                closed = true
                ticker?.cancel(false)
                ticker = null
                active.values.toList()
            }
        registrations.forEach {
            it.wait.requestStop()
            it.wait.tick(nanoTime())
        }
        queries.close()
        timer.shutdown()
    }

    private companion object {
        const val MAX_OUTER_MILLIS = 30_000L
    }
}

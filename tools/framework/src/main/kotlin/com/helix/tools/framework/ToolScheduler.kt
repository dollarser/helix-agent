package com.helix.tools.framework

import com.helix.core.model.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors

/**
 * The deterministic Tool Scheduler (roadmap HXA-037; doc 11 section 3): runs a batch of
 * already-validated [ToolDispatchRequest]s (the model's tool calls for one response)
 * through the single [ToolDispatcher] with BOUNDED, platform-decided parallelism.
 *
 * Concurrency rules (doc 11 section 3.1/3.2):
 * - total concurrency defaults to [DEFAULT_MAX_CONCURRENCY] (2) and never exceeds
 *   [HARD_MAX_CONCURRENCY] (4, pre-real-device-evidence cap);
 * - [resourceGate] may only LOWER the current allowance (low memory / background /
 *   thermal) — it can never raise it and never influences approvals or result order;
 * - two calls start in parallel ONLY when both footprints are non-exclusive (proven
 *   read-only, no Root/Accessibility action) and share no resource/origin key and no
 *   exclusive lane; any exclusive call is a full barrier (first version: writes are
 *   conservatively serialized, doc 11 section 3.1);
 * - completion may be OUT OF ORDER, but [scheduleBatch] returns the results in the
 *   ORIGINAL call sequence — model backfill order never depends on completion speed
 *   (doc 11 section 3.2, release blocker section 7);
 * - a failing item does NOT cancel other items; a cancelled turn's unstarted items go
 *   through the dispatcher with the already-set cancel signal and end in the durable
 *   CANCELLED_BEFORE_START outcome (doc 11: 未启动项得到持久 ABORTED_BEFORE_START, 已启动项
 *   收到 cancel 并等待 terminal/unknown 对账 — the batch always waits for every terminal).
 *
 * The footprints come from [EffectFootprintBuilder] over trusted facts — the model and
 * MCP annotations have no path into the parallelism decision.
 *
 * Concurrency of [scheduleBatch] itself: the in-flight slots are keyed by the call's
 * [ToolDispatchRequest.toolCallId] (globally unique, never a batch-local index), the
 * admission check AND the slot claim happen under ONE lock, and a pool rejection rolls
 * the slot back — so two batches running concurrently on the same scheduler instance
 * cannot double-book a slot, over-admit past [maxConcurrency], or see each other's
 * conflicting calls as absent. (The app today serializes turns, so batches never
 * actually overlap; this is the framework contract for when they do.)
 */
class ToolScheduler(
    private val clock: Clock,
    private val dispatcher: ToolDispatcher,
    private val registry: ToolRegistry,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    private val resourceGate: () -> Int = { DEFAULT_MAX_CONCURRENCY },
    private val resourceKeyExtractor: ResourceKeyExtractor = NoResourceKeys,
) {
    init {
        require(maxConcurrency in 1..HARD_MAX_CONCURRENCY) {
            "maxConcurrency must be in 1..$HARD_MAX_CONCURRENCY (pre-evidence hard cap): $maxConcurrency"
        }
    }

    private val pool =
        Executors.newFixedThreadPool(
            HARD_MAX_CONCURRENCY,
        ) { r -> Thread(r, "tool-scheduler").apply { isDaemon = true } }
    private val inFlightLock = Any()

    /** In-flight calls as (toolCallId, footprint): keyed by the call's GLOBALLY UNIQUE
     * id (never a batch-local index — two concurrent batches would collide on indices
     * and one batch's completion would free the other's slot). Removing by value would
     * also be wrong: two calls can have value-equal footprints. */
    private val inFlight = mutableListOf<Pair<String, EffectFootprint>>()

    /**
     * The batch's settled view: terminal outcomes IN CALL SEQUENCE plus the first
     * dispatcher exception (if any). A null outcome slot means that call's dispatch
     * threw (its [Throwable] is [error]) — the caller settles that slot durably
     * (e.g. CANCELLED on a turn stop) and then decides whether to propagate [error].
     */
    data class BatchResult(
        val outcomes: List<ToolDispatchOutcome?>,
        val error: Throwable?,
    )

    /**
     * Runs [calls] through the dispatcher with the admission rules above and returns the
     * terminal outcomes IN CALL SEQUENCE, blocking until every call reached a terminal
     * outcome (a call never "disappears": cancel and crash leave durable outcomes,
     * doc 11 section 7). One failing item does NOT cancel the others (pool independence).
     *
     * Each call is stamped with its `queuedAt` (enqueue time) before dispatch so the
     * queue wait is auditable per attempt. A dispatcher throw lands in
     * [BatchResult.error] together with the OTHER calls' settled outcomes — the scheduler
     * never swallows a dispatch failure and never loses a settled outcome.
     */
    fun scheduleBatch(calls: List<ToolDispatchRequest>): BatchResult {
        if (calls.isEmpty()) return BatchResult(emptyList(), null)
        val stamped =
            calls.map { call ->
                call.copy(queuedAt = clock.now().toEpochMilli())
            }
        val footprints =
            stamped.map { call ->
                EffectFootprintBuilder.build(
                    descriptor = resolveDescriptor(call),
                    args = call.args,
                    executionTarget = call.executionTarget,
                    scope = call.scope,
                    egress = call.egress,
                    extractor = resourceKeyExtractor,
                )
            }
        val futures = Array(stamped.size) { CompletableFuture<ToolDispatchOutcome>() }
        val submitted = BooleanArray(stamped.size)
        val pending = futures.toMutableList()
        while (pending.isNotEmpty()) {
            if (admitNext(stamped, footprints, futures, submitted)) continue
            // Nothing new may start (full or all-conflicting): wait for the next
            // completion, then re-evaluate. (A completion can free a slot or a lane.)
            // The waiter completes on the FIRST terminal state of any pending future —
            // success OR failure (a failed item settles, it never aborts the batch).
            // A plain signal future: complete on the FIRST terminal state (success or
            // failure) of any pending future; the loop re-evaluates after every step.
            val waiter = CompletableFuture<Void>()
            pending.forEach { future ->
                future.whenComplete { _, _ -> waiter.complete(null) }
            }
            waiter.join()
            // Drop exactly the futures that reached a terminal state.
            pending.removeAll { it.isDone }
        }
        return collectOutcomes(futures)
    }

    /**
     * The settled outcomes IN CALL SEQUENCE; a dispatcher throw lands in [BatchResult.error]
     * (the first one, in call order) with that call's slot null — the caller settles that
     * slot durably and decides whether to propagate the error.
     */
    @Suppress("TooGenericExceptionCaught") // the future's join wraps ANY dispatcher throw in a CompletionException
    private fun collectOutcomes(futures: Array<CompletableFuture<ToolDispatchOutcome>>): BatchResult {
        var firstError: Throwable? = null
        val outcomes =
            futures.map { future ->
                if (!future.isCompletedExceptionally) {
                    future.join()
                } else {
                    if (firstError == null) {
                        firstError = unwrapCompletion(future)
                    }
                    null
                }
            }
        return BatchResult(outcomes, firstError)
    }

    /** The cause behind a failed future (never null for a completed-exceptionally future). */
    private fun unwrapCompletion(future: CompletableFuture<ToolDispatchOutcome>): Throwable =
        try {
            future.join()
            error("future completed exceptionally but joined cleanly")
        } catch (e: CompletionException) {
            e.cause ?: e
        }

    /**
     * Scans the queue from the front and starts the EARLIEST eligible calls (fairness:
     * queue order, no re-queueing). Returns true when at least one call was started.
     */
    private fun admitNext(
        calls: List<ToolDispatchRequest>,
        footprints: List<EffectFootprint>,
        futures: Array<CompletableFuture<ToolDispatchOutcome>>,
        submitted: BooleanArray,
    ): Boolean {
        var started = false
        for (idx in calls.indices) {
            if (!submitted[idx] && tryClaimSlot(calls[idx].toolCallId, footprints[idx])) {
                submitted[idx] = true
                started = true
                // The footprint occupies its slot AT ADMISSION (before the pool thread
                // even starts): the slot count must reflect every admitted-but-not-yet-
                // complete call, including ones still waiting for a pool thread.
                submitPoolTask(calls[idx], futures[idx])
            }
        }
        return started
    }

    /**
     * Admission CHECK and slot CLAIM as ONE atomic step: a check-then-add split across
     * two lock acquisitions would let two concurrent batches both see "free" and both
     * claim, over-admitting past the cap.
     */
    private fun tryClaimSlot(
        callId: String,
        fp: EffectFootprint,
    ): Boolean =
        synchronized(inFlightLock) {
            inFlight.size < effectiveConcurrency() &&
                inFlight
                    .none { (_, other) -> other.conflictsWith(fp) }
                    .also { free ->
                        if (free) {
                            inFlight += callId to fp
                        }
                    }
        }

    // The pool task must settle its future for ANY dispatcher throw (contract violation
    // included) — the caller reads it as the batch's firstError with a null slot. A pool
    // rejection (JVM shutdown) must likewise roll the claimed slot back: one broad
    // catch per terminal path, hence the single suppression below.
    @Suppress("TooGenericExceptionCaught")
    private fun submitPoolTask(
        call: ToolDispatchRequest,
        future: CompletableFuture<ToolDispatchOutcome>,
    ) {
        val callId = call.toolCallId
        try {
            pool.execute {
                // The dispatcher's contract returns an outcome; ANY throw is a contract
                // violation that must still settle the future.
                try {
                    future.complete(dispatcher.dispatch(call))
                } catch (t: Throwable) {
                    future.completeExceptionally(t)
                } finally {
                    releaseSlot(callId)
                }
            }
        } catch (t: Throwable) {
            // The pool refused the task (shutdown): no thread will ever run it, so roll
            // the claimed slot back NOW and settle the future — otherwise the slot is
            // occupied forever (permanently lowering admission) and this call's batch
            // never terminates.
            releaseSlot(callId)
            future.completeExceptionally(t)
        }
    }

    private fun releaseSlot(callId: String) {
        synchronized(inFlightLock) {
            inFlight.removeAll { it.first == callId }
        }
    }

    /** The current allowance: the configured cap lowered (never raised) by the resource gate. */
    private fun effectiveConcurrency(): Int = minOf(maxConcurrency, resourceGate().coerceAtLeast(1))

    /**
     * The footprint descriptor MUST be the SAME contract the dispatcher executes: the
     * exact (name, version) from the request, never the registry's newest version. A
     * newer version registered between the model's tool table and the dispatch could
     * carry a different operation class (e.g. a read that is really a write in the
     * pinned version) — deciding parallelism on the wrong descriptor would let
     * conflicting calls run in parallel (doc 11 section 3.1). Unknown (name, version)
     * -> null -> the builder's conservative LOCAL_MUTATION path, the same mapping the
     * dispatcher's validation rejects later. The require is the registry's exact
     * "unknown tool" signal; the null IS the conservative mapping.
     */
    @Suppress("SwallowedException")
    private fun resolveDescriptor(call: ToolDispatchRequest): ToolDescriptor? =
        try {
            registry.resolve(call.toolName, call.toolVersion)
        } catch (e: IllegalArgumentException) {
            null
        }

    companion object {
        /** Default total concurrency (doc 11 section 3.2: 默认总并发 2). */
        const val DEFAULT_MAX_CONCURRENCY = 2

        /** Hard cap before real-device evidence (doc 11: 真机证据前不超过 4). */
        const val HARD_MAX_CONCURRENCY = 4
    }
}

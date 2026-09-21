package com.helix.tools.framework

/**
 * Application-owned admission shared by tools and manual Runtime entry points.
 * A retained owner survives its launching call; only reconciliation releases it.
 * This is not authorization, a job state machine, or a timer that assumes effects ended.
 * One application-process instance must cover every entry point using the same store.
 */
class ExecutionOwnership(
    private val store: Store,
) {
    data class Owner(
        val executionId: String,
        val generation: String,
    ) {
        init {
            require(executionId.isNotBlank() && generation.isNotBlank())
        }
    }

    /** Persist only execution identity. Runtime remains the owner of execution state. */
    interface Store {
        fun read(): Owner?

        /** Durable compare-and-set; false means another owner won, failure must throw. */
        fun compareAndSet(
            expected: Owner?,
            replacement: Owner?,
        ): Boolean
    }

    private val lock = Any()
    private val active = mutableMapOf<String, Boolean>()
    private var reconciling = false

    /** Existing scheduler still decides parallelism between ordinary calls. */
    fun acquire(
        callId: String,
        exclusive: Boolean = true,
    ): Permit? =
        synchronized(lock) {
            require(callId.isNotBlank())
            check(callId !in active) { "execution admission identity already active" }
            if (reconciling || store.read() != null) return@synchronized null
            if (active.isNotEmpty() && (exclusive || active.values.any { it })) return@synchronized null
            active[callId] = exclusive
            Permit(callId)
        }

    /**
     * Trusted host reconciliation only, after resolving the original session/call binding.
     * Keep admission across terminal proof and output import. Never derive this exemption
     * from a tool name, model arguments, or a read-only effect declaration.
     */
    fun acquireReconciliation(owner: Owner): ReconciliationPermit? =
        synchronized(lock) {
            if (active.isNotEmpty() || reconciling || store.read() != owner) return@synchronized null
            reconciling = true
            ReconciliationPermit(owner)
        }

    /** Read-only projection. It never starts a Runtime, renews a lease, or clears a holder. */
    fun retainedOwner(): Owner? = synchronized(lock) { store.read() }

    /**
     * Atomically transfers retained ownership from [expected] to [replacement].
     * Returns true if successful, false if expected owner does not match or admission is active.
     */
    fun transferRetained(
        expected: Owner,
        replacement: Owner,
    ): Boolean =
        synchronized(lock) {
            if (active.isNotEmpty() || reconciling) return@synchronized false
            if (store.read() != expected) return@synchronized false
            store.compareAndSet(expected, replacement)
        }

    /** Trusted launching executor transfers its currently held exclusive admission before IPC. */
    fun retainForCall(
        callId: String,
        owner: Owner,
    ): Boolean =
        synchronized(lock) {
            check(callId in active) { "execution admission is not active" }
            if (active.size != 1 || active[callId] != true) return@synchronized false
            val current = store.read()
            if (current != null) return@synchronized current == owner
            store.compareAndSet(null, owner)
        }

    /** Trusted launcher only, after a definitive no-submit/no-start result; its live permit still excludes writers. */
    fun releaseUnsubmittedForCall(
        callId: String,
        owner: Owner,
    ): Boolean =
        synchronized(lock) {
            check(active[callId] == true && active.size == 1) { "original exclusive launcher is not active" }
            store.compareAndSet(owner, null)
        }

    /** Acquire inside the executor thread, so a deadline cannot release a still-running effect. */
    fun guard(
        executor: ToolExecutor,
        exclusive: Boolean = true,
    ): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                when {
                    call.cancel.isCancelled() -> ToolExecutorResult.Cancelled
                    executor is ControlExecutor -> executor.runGuarded(call, this@ExecutionOwnership)
                    executor is MetadataExecutor -> executor.runGuarded(call, this@ExecutionOwnership)
                    else -> runOrdinary(executor, call, exclusive)
                }
        }

    /** Trusted composition only: closed session metadata, never a descriptor-based exemption. */
    fun metadataExecutor(executor: ToolExecutor): ToolExecutor = MetadataExecutor(executor)

    private inner class MetadataExecutor(
        private val delegate: ToolExecutor,
    ) : ToolExecutor {
        override fun execute(call: ExecutableToolCall): ToolExecutorResult =
            ToolExecutorResult.Failed("Metadata requires its host execution guard.", sideEffectFree = true)

        fun runGuarded(
            call: ExecutableToolCall,
            host: ExecutionOwnership,
        ): ToolExecutorResult {
            check(host === this@ExecutionOwnership) { "metadata belongs to another admission host" }
            if (call.sessionId.isNullOrBlank() || call.turnId.isNullOrBlank()) {
                return ToolExecutorResult.Failed(
                    "Metadata requires trusted session and turn context.",
                    sideEffectFree = true,
                )
            }
            // No execution permit or owner mutation: the bound metadata implementation keeps its own validation.
            return delegate.execute(call)
        }
    }

    /** Only trusted platform wiring supplies the binding resolver; model metadata cannot opt in. */
    fun controlExecutor(
        resolve: (ExecutableToolCall) -> Owner,
        execute: (ExecutableToolCall, ReconciliationPermit?) -> ToolExecutorResult,
    ): ToolExecutor = ControlExecutor(resolve, execute)

    private inner class ControlExecutor(
        private val resolve: (ExecutableToolCall) -> Owner,
        private val action: (ExecutableToolCall, ReconciliationPermit?) -> ToolExecutorResult,
    ) : ToolExecutor {
        override fun execute(call: ExecutableToolCall): ToolExecutorResult =
            ToolExecutorResult.Failed("Runtime control requires execution admission.", sideEffectFree = true)

        fun runGuarded(
            call: ExecutableToolCall,
            host: ExecutionOwnership,
        ): ToolExecutorResult {
            check(host === this@ExecutionOwnership) { "control belongs to another admission host" }
            val original = resolve(call)
            val retained = retainedOwner()
            return if (retained == null) {
                acquire(call.toolCallId)?.use { invoke(call, null) } ?: busy()
            } else {
                acquireReconciliation(original)?.use { invoke(call, it) } ?: busy()
            }
        }

        private fun invoke(
            call: ExecutableToolCall,
            permit: ReconciliationPermit?,
        ): ToolExecutorResult = if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else action(call, permit)

        private fun busy() =
            ToolExecutorResult.Failed("EXECUTION_BUSY: original Runtime owner is unavailable.", sideEffectFree = true)
    }

    /**
     * Called only by trusted reconciliation after the original execution is proven stopped.
     * An old result cannot release a newer generation, even when an execution ID is reused.
     */
    fun settle(owner: Owner): Boolean =
        synchronized(lock) {
            // A query can race the write-ahead holder BEFORE submission. "Not found" then
            // does not authorize releasing a launcher that can still submit afterwards.
            if (active.isNotEmpty() || reconciling) return@synchronized false
            store.compareAndSet(owner, null)
        }

    inner class ReconciliationPermit internal constructor(
        private val owner: Owner,
    ) : AutoCloseable {
        private var closed = false

        /** Call only after terminal proof and durable result settlement/import. */
        fun settle(): Boolean =
            synchronized(lock) {
                check(!closed) { "reconciliation admission already closed" }
                store.compareAndSet(owner, null)
            }

        /** Failure/uncertainty keeps the durable owner; release only this host operation. */
        override fun close() {
            synchronized(lock) {
                if (!closed) {
                    reconciling = false
                    closed = true
                }
            }
        }
    }

    inner class Permit internal constructor(
        private val callId: String,
    ) : AutoCloseable {
        private var closed = false

        /**
         * Write-ahead transfer before detached/PTY submission. Requires sole admission;
         * callers must also hold the scheduler's exclusive footprint for this transfer.
         * A failed/uncertain submit keeps the durable owner until explicit reconciliation.
         */
        fun retain(owner: Owner): Boolean =
            synchronized(lock) {
                check(!closed) { "execution admission already closed" }
                retainForCall(callId, owner)
            }

        /** Closing a launching call never releases its retained execution. */
        override fun close() {
            synchronized(lock) {
                if (!closed) {
                    active.remove(callId)
                    closed = true
                }
            }
        }
    }
}

private fun ExecutionOwnership.runOrdinary(
    executor: ToolExecutor,
    call: ExecutableToolCall,
    exclusive: Boolean,
): ToolExecutorResult {
    val permit =
        acquire(call.toolCallId, exclusive)
            ?: return ToolExecutorResult.Failed(
                "EXECUTION_BUSY: reconcile or stop the existing Runtime execution before retrying.",
                sideEffectFree = true,
            )
    return permit.use {
        if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else executor.execute(call)
    }
}

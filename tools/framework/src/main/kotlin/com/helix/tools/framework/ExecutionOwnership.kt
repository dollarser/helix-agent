package com.helix.tools.framework

/**
 * Original execution identities and per-execution control, not a global file/result lock.
 * Retained jobs and terminals do not exclude unrelated work. Only the singleton native
 * JavaScript service keeps a physical lane until exit is proven. Capacity belongs to
 * bounded execution pools and the Runtime; authorization remains in the Dispatcher.
 */
@Suppress("TooManyFunctions") // Keep ownership admission and trusted control identity in one authority.
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
        fun owners(): Set<Owner>

        /** Atomic identity-set update. A thrown write may have committed: callers inspect before cleanup. */
        fun update(
            expected: Set<Owner>,
            replacement: Set<Owner>,
        ): Boolean

        /** Single-slot projection used by the two manual terminal bindings, never the global registry. */
        fun read(): Owner? = owners().also { check(it.size <= 1) { "Not a single execution binding" } }.singleOrNull()

        fun compareAndSet(
            expected: Owner?,
            replacement: Owner?,
        ): Boolean = update(setOfNotNull(expected), setOfNotNull(replacement))
    }

    private val lock = Any()
    private val active = mutableSetOf<String>()
    private val launching = mutableMapOf<String, Owner>()
    private val nativeActive = mutableSetOf<String>()
    private val reconciling = mutableSetOf<Owner>()

    /** Existing scheduler still decides parallelism between ordinary calls. */
    fun acquire(callId: String): Permit? =
        synchronized(lock) {
            require(callId.isNotBlank())
            check(callId !in active) { "execution admission identity already active" }
            if (active.size >= MAX_ACTIVE_CALLS) return@synchronized null
            active += callId
            Permit(callId)
        }

    private fun acquireNative(callId: String): Permit? =
        synchronized(lock) {
            if (nativeActive.isNotEmpty() || store.owners().any(::isNative)) return@synchronized null
            acquire(callId)?.also { nativeActive += callId }
        }

    /**
     * Trusted host reconciliation only, after resolving the original session/call binding.
     * Keep admission across terminal proof and output import. Never derive this exemption
     * from a tool name, model arguments, or a read-only effect declaration.
     */
    fun acquireReconciliation(owner: Owner): ReconciliationPermit? =
        synchronized(lock) {
            if (owner !in store.owners()) return@synchronized null
            acquireControl(owner)
        }

    private fun acquireControl(owner: Owner): ReconciliationPermit? =
        synchronized(lock) {
            if (owner in reconciling || owner in launching.values) return@synchronized null
            val current = store.owners()
            if (current.any { it.executionId == owner.executionId && it != owner }) return@synchronized null
            reconciling += owner
            ReconciliationPermit(owner, owner in current)
        }

    /** Diagnostic snapshot only; no owner IDs, paths or authority to clear an execution. */
    fun busyFailure(): ToolExecutorResult.Failed =
        ToolExecutorResult.Failed(
            "EXECUTION_BUSY: runtime capacity or this execution's control lane is occupied; " +
                "unrelated work may continue.",
            sideEffectFree = true,
        )

    /** Read-only projection. It never starts a Runtime, renews a lease, or clears a holder. */
    fun retainedOwners(): Set<Owner> = synchronized(lock) { store.owners().toSet() }

    fun isRetained(owner: Owner): Boolean = synchronized(lock) { owner in store.owners() }

    /**
     * Atomically transfers retained ownership from [expected] to [replacement].
     * Returns true if successful, false if expected owner does not match or admission is active.
     */
    fun transferRetained(
        expected: Owner,
        replacement: Owner,
    ): Boolean =
        synchronized(lock) {
            if (expected in launching.values || expected in reconciling) return@synchronized false
            val current = store.owners()
            if (expected !in current || replacement in current) return@synchronized false
            store.update(current, current - expected + replacement)
        }

    /** Trusted launching executor records its own identity before IPC, without excluding other launches. */
    fun retainForCall(
        callId: String,
        owner: Owner,
    ): Boolean =
        synchronized(lock) {
            check(callId in active) { "execution admission is not active" }
            val previous = launching[callId]
            if (previous != null && previous != owner) return@synchronized false
            val current = store.owners()
            if (owner in current) return@synchronized previous == owner
            if (current.any { it.executionId == owner.executionId } || owner in reconciling) return@synchronized false
            if (isNative(owner) &&
                (current.any(::isNative) || nativeActive.any { it != callId })
            ) {
                return@synchronized false
            }
            // Write may commit then throw; keep the original launching identity for exact rollback.
            launching[callId] = owner
            if (isNative(owner)) nativeActive += callId
            store.update(current, current + owner)
        }

    /** Trusted launcher only, after a definitive no-submit/no-start result; affects only its own identity. */
    fun releaseUnsubmittedForCall(
        callId: String,
        owner: Owner,
    ): Boolean =
        synchronized(lock) {
            check(callId in active && launching[callId] != null) { "Original launcher is not active" }
            launching[callId] == owner && removeOwner(owner)
        }

    /** Original launcher only, after its Runtime has proved exit; this does not erase side-effect facts. */
    fun releaseStoppedForCall(
        callId: String,
        owner: Owner,
    ): Boolean =
        synchronized(lock) {
            check(callId in active && launching[callId] != null) { "Original launcher is not active" }
            launching[callId] == owner && removeOwner(owner)
        }

    /** Acquire inside the executor thread, so a deadline cannot release a still-running effect. */
    fun guard(executor: ToolExecutor): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                when {
                    call.cancel.isCancelled() -> ToolExecutorResult.Cancelled
                    executor is ControlExecutor -> executor.runGuarded(call, this@ExecutionOwnership)
                    executor is MetadataExecutor -> executor.runGuarded(call, this@ExecutionOwnership)
                    executor is NativeExecutor -> executor.runGuarded(call, this@ExecutionOwnership)
                    else -> runOrdinary(executor, call)
                }
        }

    /** Only the concrete trusted control binding may use reserved reconciliation capacity. */
    internal fun isControlExecutor(executor: ToolExecutor): Boolean = executor is ControlExecutor

    /** Only platform wiring can wrap the singleton native engine; ordinary isolated JS stays independent. */
    fun nativeExecutor(executor: ToolExecutor): ToolExecutor = NativeExecutor(executor)

    private inner class NativeExecutor(
        private val delegate: ToolExecutor,
    ) : ToolExecutor {
        override fun execute(call: ExecutableToolCall): ToolExecutorResult =
            ToolExecutorResult.Failed("Native execution requires its host guard.", sideEffectFree = true)

        fun runGuarded(
            call: ExecutableToolCall,
            host: ExecutionOwnership,
        ): ToolExecutorResult {
            check(host === this@ExecutionOwnership)
            val native = (call.args["access"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "native"
            val permit =
                (if (native) acquireNative(call.toolCallId) else acquire(call.toolCallId))
                    ?: return busyFailure()
            return permit.use {
                if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else delegate.execute(call)
            }
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
            val permit = acquireControl(original) ?: return busy()
            return permit.use {
                invoke(call, it.takeIf { it.wasRetained })
            }
        }

        private fun invoke(
            call: ExecutableToolCall,
            permit: ReconciliationPermit?,
        ): ToolExecutorResult = if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else action(call, permit)

        private fun busy() = busyFailure()
    }

    /**
     * Called only by trusted reconciliation after the original execution is proven stopped.
     * An old result cannot release a newer generation, even when an execution ID is reused.
     */
    fun settle(owner: Owner): Boolean =
        synchronized(lock) {
            // A query can race the write-ahead holder BEFORE submission. "Not found" then
            // does not authorize releasing a launcher that can still submit afterwards.
            if (owner in launching.values || owner in reconciling) return@synchronized false
            removeOwner(owner)
        }

    private fun removeOwner(owner: Owner): Boolean {
        val current = store.owners()
        return owner in current && store.update(current, current - owner)
    }

    inner class ReconciliationPermit internal constructor(
        private val owner: Owner,
        internal val wasRetained: Boolean,
    ) : AutoCloseable {
        private var closed = false

        /** Call only after terminal proof and durable result settlement/import. */
        fun settle(): Boolean =
            synchronized(lock) {
                check(!closed) { "reconciliation admission already closed" }
                removeOwner(owner)
            }

        /** Failure/uncertainty keeps the durable owner; release only this host operation. */
        override fun close() {
            synchronized(lock) {
                if (!closed) {
                    reconciling.remove(owner)
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
         * Write-ahead identity before detached/PTY submission, independent of unrelated calls.
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
                    nativeActive.remove(callId)
                    launching.remove(callId)
                    closed = true
                }
            }
        }
    }

    private companion object {
        const val MAX_ACTIVE_CALLS = 32

        fun isNative(owner: Owner): Boolean = owner.executionId.startsWith("quickjs-native:")
    }
}

private fun ExecutionOwnership.runOrdinary(
    executor: ToolExecutor,
    call: ExecutableToolCall,
): ToolExecutorResult {
    val permit =
        acquire(call.toolCallId)
            ?: return busyFailure()
    return permit.use {
        if (call.cancel.isCancelled()) ToolExecutorResult.Cancelled else executor.execute(call)
    }
}

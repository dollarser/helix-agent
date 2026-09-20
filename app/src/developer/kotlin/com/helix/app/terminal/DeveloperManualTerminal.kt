package com.helix.app.terminal

import android.content.Context
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.proot.ExecutionOwnershipStore
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.client.PtySessionClient
import com.helix.runtime.proot.ipc.PtySessionKey
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.helix.runtime.proot.ipc.PtySessionProtocol as Wire

/** Shares the exact host admission instance used by tools. Construction performs no IPC. */
internal class DeveloperManualTerminal(
    private val context: Context,
    private val ownership: ExecutionOwnership,
    private val profile: SafetyProfileStore,
) : ManualTerminal {
    private val binding1 = ExecutionOwnershipStore(File(context.filesDir, "execution-admission/manual-terminal"))
    private val binding2 = ExecutionOwnershipStore(File(context.filesDir, "execution-admission/manual-terminal-2"))
    private val mutex = Mutex()

    private fun bindings() = listOf(binding1, binding2)

    override suspend fun hasSession(): Boolean =
        withContext(Dispatchers.IO) {
            binding1.read() != null || binding2.read() != null
        }

    override suspend fun sessions(): List<ManualTerminal.State> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val owners = bindings().mapNotNull { it.read() }
                if (owners.isEmpty()) return@withContext emptyList()
                PtySessionClient(context).use { client ->
                    client.connect()
                    owners.mapNotNull { owner ->
                        val reply = client.request(key(owner), Wire.QUERY)
                        reply.record?.let { terminalState(reply) }
                    }
                }
            }
        }

    override suspend fun start(
        relativeDirectory: String,
        leaseMs: Long,
    ): ManualTerminal.State =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(profile.profile == SafetyProfile.ADVANCED) { "Manual terminal requires Advanced" }
                require(leaseMs in 1000..Wire.MAX_LEASE_MS)
                val targetBinding: ExecutionOwnershipStore
                val isPrimary: Boolean
                if (binding1.read() == null) {
                    targetBinding = binding1
                    isPrimary = true
                } else if (binding2.read() == null) {
                    targetBinding = binding2
                    isPrimary = false
                } else {
                    error("Manual terminal capacity exhausted (max 2 sessions)")
                }

                val root = File(context.filesDir, "workspaces/app").canonicalFile
                val workspace = File(root, relativeDirectory).canonicalFile
                require(workspace.isDirectory && workspace.toPath().startsWith(root.toPath()))
                val owner = ExecutionOwnership.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                val key = key(owner)

                if (isPrimary) {
                    checkNotNull(ownership.acquire("manual-${key.sessionId}")) { "Execution is busy" }.use { permit ->
                        check(targetBinding.compareAndSet(null, owner))
                        check(permit.retain(owner))
                        PtySessionClient(context).use { client ->
                            client.connect()
                            val reply =
                                client.request(key, Wire.START) { data ->
                                    data.writeString(workspace.path)
                                    data.writeLong(leaseMs)
                                }
                            if (reply.outcome == "START_REFUSED" || reply.outcome == "CAPACITY_EXHAUSTED") {
                                check(ownership.releaseUnsubmittedForCall("manual-${key.sessionId}", owner))
                                check(targetBinding.compareAndSet(owner, null))
                                error("Manual terminal was not started (${reply.outcome}); check Runtime readiness")
                            }
                            terminalState(reply)
                        }
                    }
                } else {
                    check(targetBinding.compareAndSet(null, owner))
                    PtySessionClient(context).use { client ->
                        client.connect()
                        val reply =
                            client.request(key, Wire.START) { data ->
                                data.writeString(workspace.path)
                                data.writeLong(leaseMs)
                            }
                        if (reply.outcome == "START_REFUSED" || reply.outcome == "CAPACITY_EXHAUSTED") {
                            check(targetBinding.compareAndSet(owner, null))
                            error("Manual terminal was not started (${reply.outcome}); check Runtime readiness")
                        }
                        terminalState(reply)
                    }
                }
            }
        }

    override suspend fun query(): ManualTerminal.State = query(sessionId = null)

    override suspend fun query(sessionId: String?): ManualTerminal.State = operation(Wire.QUERY, sessionId)

    override suspend fun stop(): ManualTerminal.State = stop(sessionId = null)

    override suspend fun stop(sessionId: String?): ManualTerminal.State = operation(Wire.STOP, sessionId)

    override suspend fun settle() = settle(sessionId = null)

    override suspend fun settle(sessionId: String?) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val (targetBinding, owner) = findOwner(sessionId)
                PtySessionClient(context).use { client ->
                    client.connect()
                    val observed = client.request(key(owner), Wire.QUERY)
                    check(observed.record?.stopProof != null) { "Execution not proven stopped" }
                    val ack = client.request(key(owner), Wire.ACK)
                    check(ack.record?.reconciled == true)

                    if (targetBinding == binding2) {
                        check(binding2.compareAndSet(owner, null))
                    } else {
                        // binding1 is being settled.
                        val remainingOwner = binding2.read()
                        if (remainingOwner != null) {
                            // Promote remaining session to binding1 and swap retained admission
                            val admissionStore =
                                ExecutionOwnershipStore(File(context.filesDir, "execution-admission/owner"))
                            admissionStore.compareAndSet(owner, remainingOwner)
                            check(binding1.compareAndSet(owner, remainingOwner))
                            check(binding2.compareAndSet(remainingOwner, null))
                        } else {
                            // No other session, settle the entire host admission
                            val retained = ownership.retainedOwner()
                            if (retained == owner) {
                                val reconciliation =
                                    checkNotNull(ownership.acquireReconciliation(owner)) { "Reconciliation busy" }
                                reconciliation.use { permit ->
                                    check(permit.settle())
                                }
                            }
                            check(binding1.compareAndSet(owner, null))
                        }
                    }
                }
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun attach(): ManualTerminal.Connection = attach(sessionId = null)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun attach(sessionId: String?): ManualTerminal.Connection {
        var acquired: ManualTerminalConnection? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    check(profile.profile == SafetyProfile.ADVANCED)
                    val (_, owner) = findOwner(sessionId)
                    ManualTerminalConnection(context, key(owner)) {
                        profile.profile == SafetyProfile.ADVANCED
                    }.connect().also { acquired = it }
                }
            }
        } catch (failure: Exception) {
            try {
                acquired?.detach()
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private suspend fun operation(
        code: Int,
        sessionId: String?,
    ): ManualTerminal.State =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val (_, owner) = findOwner(sessionId)
                PtySessionClient(context).use { client ->
                    client.connect()
                    terminalState(client.request(key(owner), code))
                }
            }
        }

    private fun findOwner(sessionId: String?): Pair<ExecutionOwnershipStore, ExecutionOwnership.Owner> {
        if (sessionId != null) {
            for (binding in bindings()) {
                val owner = binding.read()
                if (owner != null && owner.executionId == sessionId) {
                    return binding to owner
                }
            }
            error("Terminal session not found: $sessionId")
        }
        for (binding in bindings()) {
            val owner = binding.read()
            if (owner != null) return binding to owner
        }
        error("No active terminal session")
    }

    private fun key(owner: ExecutionOwnership.Owner): PtySessionKey =
        PtySessionKey(owner.executionId, owner.generation, owner.executionId)
}

internal fun terminalState(reply: com.helix.runtime.proot.ipc.PtySessionReply): ManualTerminal.State {
    val record = checkNotNull(reply.record) { reply.outcome }
    return ManualTerminal.State(
        record.origin.sessionId,
        record.phase.name,
        record.origin.workspace,
        record.stopReason?.name,
        record.exitStatus,
        record.stopProof != null,
    )
}

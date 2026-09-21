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

    private fun reconcileBindings() {
        val retained = ownership.retainedOwner()
        val b1 = binding1.read()
        val b2 = binding2.read()
        if (b1 != null && b2 != null && b1 == b2) {
            binding2.compareAndSet(b2, null)
        } else if (retained != null && b2 == retained && b1 != retained) {
            if (binding1.compareAndSet(b1, retained)) {
                binding2.compareAndSet(retained, null)
            }
        }
    }

    override suspend fun hasSession(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                reconcileBindings()
                binding1.read() != null || binding2.read() != null
            }
        }

    override suspend fun sessions(): List<ManualTerminal.State> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                reconcileBindings()
                val owners = listOfNotNull(binding1.read(), binding2.read())
                if (owners.isEmpty()) return@withContext emptyList()
                PtySessionClient(context).use { client ->
                    client.connect()
                    owners.mapNotNull { owner ->
                        val reply = client.request(ptyKey(owner), Wire.QUERY)
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
                reconcileBindings()
                val (targetBinding, isPrimary) = selectTargetBinding(binding1, binding2)
                val root = File(context.filesDir, "workspaces/app").canonicalFile
                val workspace = File(root, relativeDirectory).canonicalFile
                require(workspace.isDirectory && workspace.toPath().startsWith(root.toPath()))
                val owner = ExecutionOwnership.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                val key = ptyKey(owner)

                if (isPrimary) {
                    checkNotNull(ownership.acquire("manual-${key.sessionId}")) { "Execution is busy" }.use { permit ->
                        check(targetBinding.compareAndSet(null, owner))
                        check(permit.retain(owner))
                        val reply = launchSession(context, key, workspace, leaseMs)
                        if (reply.outcome == "START_REFUSED" || reply.outcome == "CAPACITY_EXHAUSTED") {
                            check(ownership.releaseUnsubmittedForCall("manual-${key.sessionId}", owner))
                            check(targetBinding.compareAndSet(owner, null))
                            error("Manual terminal was not started (${reply.outcome}); check Runtime readiness")
                        }
                        terminalState(reply)
                    }
                } else {
                    check(targetBinding.compareAndSet(null, owner))
                    val reply = launchSession(context, key, workspace, leaseMs)
                    if (reply.outcome == "START_REFUSED" || reply.outcome == "CAPACITY_EXHAUSTED") {
                        check(targetBinding.compareAndSet(owner, null))
                        error("Manual terminal was not started (${reply.outcome}); check Runtime readiness")
                    }
                    terminalState(reply)
                }
            }
        }

    override suspend fun query(sessionId: String?): ManualTerminal.State = operation(Wire.QUERY, sessionId)

    override suspend fun stop(sessionId: String?): ManualTerminal.State = operation(Wire.STOP, sessionId)

    override suspend fun settle(sessionId: String?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                reconcileBindings()
                val (targetBinding, owner) = findOwner(sessionId)
                PtySessionClient(context).use { client ->
                    client.connect()
                    val observed = client.request(ptyKey(owner), Wire.QUERY)
                    check(observed.record?.stopProof != null) { "Execution not proven stopped" }
                    val ack = client.request(ptyKey(owner), Wire.ACK)
                    check(ack.record?.reconciled == true)

                    if (targetBinding == binding2) {
                        check(binding2.compareAndSet(owner, null))
                    } else {
                        // binding1 is being settled.
                        val remainingOwner = binding2.read()
                        if (remainingOwner != null) {
                            // Atomic promotion protocol:
                            check(ownership.transferRetained(owner, remainingOwner)) {
                                "Failed to transfer retained ownership"
                            }
                            check(binding1.compareAndSet(owner, remainingOwner)) {
                                "Failed to promote remaining session to binding1"
                            }
                            check(binding2.compareAndSet(remainingOwner, null)) {
                                "Failed to clear binding2"
                            }
                        } else {
                            // No other session, settle the entire host admission
                            val retained = ownership.retainedOwner()
                            if (retained == owner) {
                                val reconciliation =
                                    checkNotNull(ownership.acquireReconciliation(owner)) { "Reconciliation busy" }
                                reconciliation.use { permit ->
                                    check(permit.settle()) { "Failed to settle retained ownership" }
                                }
                            }
                            check(binding1.compareAndSet(owner, null)) { "Failed to clear binding1" }
                        }
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun attach(sessionId: String?): ManualTerminal.Connection {
        var acquired: ManualTerminalConnection? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    check(profile.profile == SafetyProfile.ADVANCED)
                    val (_, owner) = findOwner(sessionId)
                    val conn =
                        ManualTerminalConnection(context, ptyKey(owner)) {
                            profile.profile == SafetyProfile.ADVANCED
                        }.connect()
                    acquired = conn
                    if (sessionId == null) {
                        check(conn.isWriter) { "Writer is busy" }
                    }
                    conn
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
                    terminalState(client.request(ptyKey(owner), code))
                }
            }
        }

    private fun findOwner(sessionId: String?): Pair<ExecutionOwnershipStore, ExecutionOwnership.Owner> {
        reconcileBindings()
        val b1 = binding1.read()
        val b2 = binding2.read()
        val pair =
            when {
                sessionId != null -> {
                    when {
                        b1?.executionId == sessionId -> binding1 to b1
                        b2?.executionId == sessionId -> binding2 to b2
                        else -> error("Terminal session not found: $sessionId")
                    }
                }

                b1 != null -> {
                    binding1 to b1
                }

                b2 != null -> {
                    binding2 to b2
                }

                else -> {
                    error("No active terminal session")
                }
            }
        return pair
    }
}

private fun ptyKey(owner: ExecutionOwnership.Owner): PtySessionKey =
    PtySessionKey(owner.executionId, owner.generation, owner.executionId)

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

private fun selectTargetBinding(
    binding1: ExecutionOwnershipStore,
    binding2: ExecutionOwnershipStore,
): Pair<ExecutionOwnershipStore, Boolean> =
    when {
        binding1.read() == null -> binding1 to true
        binding2.read() == null -> binding2 to false
        else -> error("Manual terminal capacity exhausted (max 2 sessions)")
    }

private fun launchSession(
    context: Context,
    key: PtySessionKey,
    workspace: File,
    leaseMs: Long,
) = PtySessionClient(context).use { client ->
    client.connect()
    client.request(key, Wire.START) { data ->
        data.writeString(workspace.path)
        data.writeLong(leaseMs)
    }
}

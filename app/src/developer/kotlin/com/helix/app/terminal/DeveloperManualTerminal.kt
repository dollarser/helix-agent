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
    private val binding = ExecutionOwnershipStore(File(context.filesDir, "execution-admission/manual-terminal"))
    private val mutex = Mutex()

    override suspend fun hasSession(): Boolean = withContext(Dispatchers.IO) { binding.read() != null }

    override suspend fun start(
        relativeDirectory: String,
        leaseMs: Long,
    ): ManualTerminal.State =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(profile.profile == SafetyProfile.ADVANCED) { "Manual terminal requires Advanced" }
                require(leaseMs in 1000..Wire.MAX_LEASE_MS)
                check(binding.read() == null) { "Reconcile the existing manual session first" }
                val root = File(context.filesDir, "workspaces/app").canonicalFile
                val workspace = File(root, relativeDirectory).canonicalFile
                require(workspace.isDirectory && workspace.toPath().startsWith(root.toPath()))
                val owner = ExecutionOwnership.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                val key = key(owner)
                checkNotNull(ownership.acquire("manual-${key.sessionId}")) { "Execution is busy" }.use { permit ->
                    check(binding.compareAndSet(null, owner))
                    check(permit.retain(owner))
                    PtySessionClient(context).use { client ->
                        client.connect()
                        val reply =
                            client.request(key, Wire.START) { data ->
                                data.writeString(workspace.path)
                                data.writeLong(leaseMs)
                            }
                        if (reply.outcome == "START_REFUSED") {
                            check(ownership.releaseUnsubmittedForCall("manual-${key.sessionId}", owner))
                            check(binding.compareAndSet(owner, null))
                            error("Manual terminal was not started; check Runtime readiness")
                        }
                        terminalState(reply)
                    }
                }
            }
        }

    override suspend fun query(): ManualTerminal.State = operation(Wire.QUERY)

    override suspend fun stop(): ManualTerminal.State = operation(Wire.STOP)

    override suspend fun settle() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val owner = checkNotNull(binding.read())
                val retained = ownership.retainedOwner()
                if (retained == null) {
                    // Binding was written before admission, or admission already settled before a crash.
                    check(binding.compareAndSet(owner, null))
                } else {
                    check(retained == owner)
                    val reconciliation = checkNotNull(ownership.acquireReconciliation(owner)) { "Reconciliation busy" }
                    reconciliation.use { permit ->
                        PtySessionClient(context).use { client ->
                            client.connect()
                            val observed = client.request(key(owner), Wire.QUERY)
                            check(observed.record?.stopProof != null) {
                                "Execution not proven stopped"
                            }
                            val ack = client.request(key(owner), Wire.ACK)
                            check(ack.record?.reconciled == true)
                            check(permit.settle())
                            check(binding.compareAndSet(owner, null))
                        }
                    }
                }
            }
        }

    // Prompt coroutine cancellation must not lose a successfully acquired single-writer connection.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun attach(): ManualTerminal.Connection {
        var acquired: ManualTerminalConnection? = null
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    check(profile.profile == SafetyProfile.ADVANCED)
                    val owner = checkNotNull(binding.read())
                    check(ownership.retainedOwner() == owner)
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

    private suspend fun operation(code: Int): ManualTerminal.State =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val owner = checkNotNull(binding.read())
                PtySessionClient(context).use { client ->
                    client.connect()
                    terminalState(client.request(key(owner), code))
                }
            }
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

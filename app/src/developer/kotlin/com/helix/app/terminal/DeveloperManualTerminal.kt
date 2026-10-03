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
    private val directoryResolver: (String) -> File = { reference ->
        val root = File(context.filesDir, "workspaces/app").canonicalFile
        File(root, reference).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) }
    },
) : ManualTerminal {
    private val binding1 = ExecutionOwnershipStore(File(context.filesDir, "execution-admission/manual-terminal"))
    private val binding2 = ExecutionOwnershipStore(File(context.filesDir, "execution-admission/manual-terminal-2"))
    private val mutex = Mutex()

    override suspend fun hasSession(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                reconcileBindings(binding1, binding2)
                binding1.read() != null || binding2.read() != null
            }
        }

    override suspend fun sessions(): List<ManualTerminal.State> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                reconcileBindings(binding1, binding2)
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
                reconcileBindings(binding1, binding2)
                val targetBinding = selectTargetBinding(binding1, binding2)
                val workspace = directoryResolver(relativeDirectory)
                require(workspace.isDirectory)
                val owner = ExecutionOwnership.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                val key = ptyKey(owner)

                val reply =
                    TerminalStartTransaction(ownership, targetBinding).launch(
                        "manual-${key.sessionId}",
                        owner,
                        submit = { submitting -> launchSession(context, key, workspace, leaseMs, submitting) },
                        refused = { it.outcome == "START_REFUSED" || it.outcome == "CAPACITY_EXHAUSTED" },
                    )
                terminalState(reply)
            }
        }

    override suspend fun query(sessionId: String?): ManualTerminal.State = operation(Wire.QUERY, sessionId)

    override fun withWorkspaceCleanup(
        workspace: File,
        cleanup: () -> Unit,
    ) = kotlinx.coroutines.runBlocking {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = workspace.canonicalFile.toPath()
                val owners = listOfNotNull(binding1.read(), binding2.read())
                if (owners.isNotEmpty()) {
                    PtySessionClient(context).use { client ->
                        client.connect()
                        owners.forEach { owner ->
                            val record =
                                checkNotNull(client.request(ptyKey(owner), Wire.QUERY).record) {
                                    "Terminal workspace is unknown; workspace retained"
                                }
                            check(!File(record.origin.workspace).canonicalFile.toPath().startsWith(root)) {
                                "Terminal still retains this workspace"
                            }
                        }
                    }
                }
                cleanup()
            }
        }
    }

    override suspend fun stop(sessionId: String?): ManualTerminal.State = operation(Wire.STOP, sessionId)

    override suspend fun settle(sessionId: String?) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                reconcileBindings(binding1, binding2)
                val (targetBinding, owner) = findOwner(sessionId)
                PtySessionClient(context).use { client ->
                    client.connect()
                    val observed = client.request(ptyKey(owner), Wire.QUERY)
                    check(observed.record?.stopProof != null) { "Execution not proven stopped" }
                    val ack = client.request(ptyKey(owner), Wire.ACK)
                    check(ack.record?.reconciled == true)

                    if (ownership.isRetained(owner)) {
                        checkNotNull(ownership.acquireReconciliation(owner)) { "This terminal is being settled" }
                            .use { check(it.settle()) { "Terminal identity changed" } }
                    }
                    check(targetBinding.compareAndSet(owner, null)) { "Terminal binding changed" }
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
        reconcileBindings(binding1, binding2)
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

private fun reconcileBindings(
    binding1: ExecutionOwnershipStore,
    binding2: ExecutionOwnershipStore,
) {
    val b1 = binding1.read()
    val b2 = binding2.read()
    if (b1 != null && b2 != null && b1 == b2) binding2.compareAndSet(b2, null)
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
): ExecutionOwnershipStore =
    when {
        binding1.read() == null -> binding1
        binding2.read() == null -> binding2
        else -> error("Manual terminal capacity exhausted (max 2 sessions)")
    }

private fun launchSession(
    context: Context,
    key: PtySessionKey,
    workspace: File,
    leaseMs: Long,
    submitting: () -> Unit,
) = PtySessionClient(context).use { client ->
    client.connect()
    submitting()
    client.request(key, Wire.START) { data ->
        data.writeString(workspace.path)
        data.writeLong(leaseMs)
    }
}

package com.helix.app.terminal

import android.content.Context
import android.provider.Settings
import com.helix.app.internal.PrefsLineStore
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.proot.ExecutionOwnershipStore
import com.helix.core.model.SafetyProfile
import com.helix.runtime.proot.client.PtySessionClient
import com.helix.runtime.proot.core.RuntimeExecutionCapacity
import com.helix.runtime.proot.ipc.PtySessionKey
import com.helix.runtime.proot.ipc.PtySessionReply
import com.helix.tools.framework.ExecutionOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.helix.runtime.proot.ipc.PtySessionProtocol as Wire

/** Shares the exact host admission instance used by tools. Construction performs no IPC. */
@Suppress("TooManyFunctions") // Manual operations plus exact-identity settlement share this admission mutex.
internal class DeveloperManualTerminal(
    private val context: Context,
    private val ownership: ExecutionOwnership,
    private val profile: SafetyProfileStore,
    private val directoryResolver: (String) -> File = { reference ->
        val root = File(context.filesDir, "workspaces/app").canonicalFile
        File(root, reference).canonicalFile.also { require(it.toPath().startsWith(root.toPath())) }
    },
) : ManualTerminal {
    override val maxSessions: Int
        get() = Runtime.getRuntime().availableProcessors().coerceIn(1, RuntimeExecutionCapacity.MAX_TERMINALS)

    private val identities = File(context.filesDir, "execution-admission/terminals")
    private val bindings =
        ManualTerminalBindings(
            stores = {
                if (!identities.exists()) {
                    emptyList()
                } else {
                    checkNotNull(identities.listFiles()) { "Terminal identities unavailable" }
                        .filter { it.isFile && it.name.endsWith(".owner") }
                        .sortedByDescending { it.lastModified() }
                        .map(::ExecutionOwnershipStore)
                }
            },
            allocate = { ExecutionOwnershipStore(File(identities, "${UUID.randomUUID()}.owner")) },
        )
    private val mutex = Mutex()
    private val bootRecovery =
        TerminalBootRecovery(PrefsLineStore(context, "terminal-boot-recovery", synchronous = true))

    override suspend fun hasSession(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                bindings.owners().isNotEmpty()
            }
        }

    override suspend fun sessions(): List<ManualTerminal.State> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val owners = bindings.owners().map { it.second }
                if (owners.isEmpty()) return@withContext emptyList()
                PtySessionClient(context).use { client ->
                    client.connect()
                    owners.map { owner ->
                        val reply = client.request(ptyKey(owner), Wire.QUERY)
                        recoverState(reply, owner)
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
                val targetBinding = bindings.fresh()
                val workspace = directoryResolver(relativeDirectory)
                require(workspace.isDirectory)
                val owner = ExecutionOwnership.Owner(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                val key = ptyKey(owner)

                val reply =
                    kotlinx.coroutines.runInterruptible {
                        TerminalStartTransaction(ownership, targetBinding).launch(
                            "manual-${key.sessionId}",
                            owner,
                            submit = { submitting -> launchSession(context, key, workspace, leaseMs, submitting) },
                            refused = { it.outcome == "START_REFUSED" || it.outcome == "CAPACITY_EXHAUSTED" },
                            refusalDetail = {
                                if (it.outcome == "CAPACITY_EXHAUSTED") "CAPACITY_FULL" else "Runtime not ready"
                            },
                        )
                    }
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
                val owners = bindings.owners().map { it.second }
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
                val (targetBinding, owner) = bindings.find(sessionId)
                PtySessionClient(context).use { client ->
                    client.connect()
                    settleBinding(client, targetBinding, owner)
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
                    val (_, owner) = bindings.find(sessionId)
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
                val (_, owner) = bindings.find(sessionId)
                PtySessionClient(context).use { client ->
                    client.connect()
                    recoverState(client.request(ptyKey(owner), code), owner)
                }
            }
        }

    private fun currentBoot(): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrNull()
            ?.takeIf { it >= 0 }

    private fun recoverState(
        reply: PtySessionReply,
        owner: ExecutionOwnership.Owner,
    ): ManualTerminal.State {
        val state = terminalState(reply, owner)
        return if (reply.record == null && bootRecovery.observe(owner, currentBoot())) {
            state.copy(stopReason = "DEVICE_REBOOT_JOURNAL_MISSING", canSettle = true)
        } else {
            state
        }
    }

    private fun settleBinding(
        client: PtySessionClient,
        binding: ExecutionOwnership.Store,
        owner: ExecutionOwnership.Owner,
    ) {
        val reply = client.request(ptyKey(owner), Wire.QUERY)
        if (reply.record == null) {
            check(reply.outcome == "NOT_FOUND") { "Original terminal query failed" }
            bootRecovery.settle(owner, currentBoot(), ownership, binding)
            return
        }
        settleTerminalBinding(
            ownership,
            binding,
            owner,
            query = { client.request(ptyKey(owner), Wire.QUERY).record },
            acknowledge = { client.request(ptyKey(owner), Wire.ACK).record },
        )
    }
}

private fun ptyKey(owner: ExecutionOwnership.Owner): PtySessionKey =
    PtySessionKey(owner.executionId, owner.generation, owner.executionId)

internal fun terminalState(
    reply: com.helix.runtime.proot.ipc.PtySessionReply,
    retainedOwner: ExecutionOwnership.Owner? = null,
): ManualTerminal.State {
    val record =
        reply.record ?: run {
            check(reply.outcome == "NOT_FOUND" && retainedOwner != null) { reply.outcome }
            // A submitted START can have no journal. Keep its exact identity visible, never silently free it.
            return ManualTerminal.State(retainedOwner.executionId, "UNKNOWN", ".", "START_UNCONFIRMED", null, false)
        }
    return ManualTerminal.State(
        record.origin.sessionId,
        record.phase.name,
        record.origin.workspace,
        record.stopReason?.name,
        record.exitStatus,
        record.stopProof != null,
    )
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
    val reply =
        client.request(key, Wire.START) { data ->
            data.writeString(workspace.path)
            data.writeLong(leaseMs)
        }
    if (reply.record == null) {
        reply
    } else {
        awaitShellPrompt(
            query = { client.request(key, Wire.QUERY) },
            read = { cursor -> checkNotNull(client.request(key, Wire.READ) { it.writeString(cursor) }.page) },
        )
    }
}

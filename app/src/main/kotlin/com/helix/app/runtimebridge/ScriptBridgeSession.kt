package com.helix.app.runtimebridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import com.helix.core.model.CancelSignal
import com.helix.core.model.ToolDispatchOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.HostToolBridge
import com.helix.tools.framework.ToolBridgeEndpoint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Ephemeral transport owner only. Room/gateway remain the task, permission and result authorities. */
internal class ScriptBridgeSession(
    private val parent: ExecutableToolCall,
    private val active: (Boolean) -> Boolean,
    private val dispatch: (ExecutableToolCall, String, String, JsonObject, CancelSignal) -> ToolDispatchOutcome,
    private val jobPeer: (String, Int, Int) -> Boolean,
    private val jobActive: (String) -> Boolean,
) : HostToolBridge {
    private val channel = UUID.randomUUID().toString().replace("-", "")
    override val endpoint =
        ToolBridgeEndpoint(
            "helix-tools-$channel",
            ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) },
        )
    private val server = LocalServerSocket(endpoint.socketName)
    private val closed = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val unresolved = ConcurrentHashMap.newKeySet<String>()
    private val review = AtomicBoolean(false)
    override val reviewRequired: Boolean get() = review.get()
    override val unresolvedCallIds: Set<String> get() = unresolved.toSet()
    private val workers = Executors.newFixedThreadPool(4) { Thread(it, "helix-script-rpc").apply { isDaemon = true } }
    private val watch =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "helix-script-owner").apply {
                isDaemon =
                    true
            }
        }

    @Volatile private var peer: Pair<Int, Int>? = null

    @Volatile private var job: String? = null

    @Volatile private var detached = false

    @Volatile override var executionDeadline: Instant = parent.deadline
        private set
    override val retainingExecution: Boolean get() =
        detached &&
            try {
                job?.let(jobActive) == true
            } catch (_: java.io.IOException) {
                false
            } catch (_: RuntimeException) {
                false
            }

    init {
        watch.scheduleWithFixedDelay({ if (!live()) closeNow() }, 250, 250, TimeUnit.MILLISECONDS)
        Thread({ accept() }, "helix-script-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun live(): Boolean =
        !closed.get() && !reviewRequired && !parent.cancel.isCancelled() &&
            Instant.now().isBefore(executionDeadline) &&
            try {
                active(retainingExecution)
            } catch (_: RuntimeException) {
                false
            }

    override fun bindPeer(
        pid: Int,
        uid: Int,
    ) {
        check(pid > 1 && uid >= 10_000 && (peer == null || peer == (pid to uid))) { "BRIDGE_PEER_CHANGED" }
        peer = pid to uid
    }

    override fun bindJob(
        jobId: String,
        detached: Boolean,
        deadline: Instant,
    ) {
        require(jobId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        check(job == null) { "BRIDGE_JOB_ALREADY_BOUND" }
        job = jobId
        this.detached = detached
        executionDeadline = if (detached) deadline else minOf(parent.deadline, deadline)
    }

    override fun acceptsPeer(
        pid: Int,
        uid: Int,
    ): Boolean =
        live() &&
            try {
                peer?.let { it == (pid to uid) }
                    ?: (uid == Process.myUid() && job?.let { jobPeer(it, pid, uid) } == true)
            } catch (_: java.io.IOException) {
                false
            } catch (_: RuntimeException) {
                false
            }

    override fun request(json: String): String {
        val request =
            try {
                ScriptBridgeProtocol.decode(json, null)
            } catch (_: IllegalArgumentException) {
                return ScriptBridgeProtocol.failure("BRIDGE_REQUEST_INVALID")
            }
        return invoke(request, AtomicBoolean(false))
    }

    @Suppress("ReturnCount") // Revocation and duplicates precede dispatch.
    private fun invoke(request: ScriptBridgeProtocol.Request, abandoned: AtomicBoolean): String {
        if (!live()) return ScriptBridgeProtocol.failure("BRIDGE_CLOSED")
        if (!seen.add(request.id)) return ScriptBridgeProtocol.failure("BRIDGE_REQUEST_ALREADY_SEEN")
        val id = "script-$channel-${request.id}"
        unresolved.add(id)
        val cancel =
            object : CancelSignal {
                override fun isCancelled() = abandoned.get() || !live()
            }
        return try {
            val outcome = dispatch(parent.copy(hostToolBridge = this), id, request.tool, request.args, cancel)
            val reply = bridgeResult(id, outcome)
            if (outcome is ToolDispatchOutcome.ExecutionFailed && outcome.requiresReview) {
                review.set(true)
            } else {
                unresolved.remove(id)
            }
            reply
        } catch (_: com.helix.core.model.ApprovalWaitCancelledException) {
            unresolved.remove(id)
            bridgeResult(id, ToolDispatchOutcome.Cancelled)
        } catch (_: Exception) {
            review.set(true)
            // Gateway failures can follow an effect or durable commit. Never claim no dispatch or retry.
            buildJsonObject {
                put("status", "UNKNOWN")
                put("callId", id)
                put("requiresReview", true)
                put("sideEffectFree", false)
            }.toString()
        } finally {
            if (id in unresolved) review.set(true)
        }
    }

    private fun accept() {
        while (!closed.get()) {
            val socket =
                try {
                    server.accept()
                } catch (_: java.io.IOException) {
                    break
                }
            val uid =
                try {
                    socket.peerCredentials.uid
                } catch (_: java.io.IOException) {
                    -1
                }
            if (uid != Process.myUid() || closed.get() || clients.size >= 4) {
                socket.close()
            } else {
                clients.add(socket)
                Thread({ serve(socket) }, "helix-script-socket").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    private fun serve(socket: LocalSocket) {
        val abandoned = AtomicBoolean(false)
        var attempted = false
        try {
            socket.soTimeout = 2_000
            val input = socket.inputStream.buffered()
            val body = readBridgeLine(input)
            val request = ScriptBridgeProtocol.decode(body, endpoint.proof)
            val credentials = socket.peerCredentials
            val peerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!acceptsPeer(credentials.pid, credentials.uid) && live() && System.nanoTime() < peerDeadline) {
                Thread.sleep(10)
            }
            check(acceptsPeer(credentials.pid, credentials.uid)) { "BRIDGE_PEER_REFUSED" }
            attempted = true
            val future = workers.submit<String> { invoke(request, abandoned) }
            val descriptor =
                android.system.StructPollfd().apply {
                    fd = socket.fileDescriptor
                    events =
                        (
                            android.system.OsConstants.POLLIN or android.system.OsConstants.POLLHUP or
                                android.system.OsConstants.POLLERR
                        ).toShort()
                }
            while (!future.isDone && live()) {
                // LocalSocket's read timeout is not a portable SocketTimeoutException.
                // Poll the kernel descriptor: no readability means the peer is still waiting,
                // not EOF. A close or unexpected second request abandons only this attempt.
                if (input.available() > 0 || android.system.Os.poll(arrayOf(descriptor), 100) > 0) {
                    abandoned.set(true)
                    return
                }
            }
            if (!live()) {
                abandoned.set(true)
                return
            }
            val reply = future.get()
            socket.outputStream.write((reply + "\n").toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
        } catch (_: Exception) {
            abandoned.set(true)
            if (!attempted) {
                try {
                    val refusal = ScriptBridgeProtocol.failure("BRIDGE_REQUEST_OR_PEER_REFUSED") + "\n"
                    socket.outputStream.write(refusal.toByteArray(Charsets.UTF_8))
                    socket.outputStream.flush()
                } catch (_: java.io.IOException) {
                    // Peer already closed; no dispatch occurred.
                }
            }
            // No request body, endpoint proof or exception text is logged.
        } finally {
            clients.remove(socket)
            socket.close()
        }
    }

    override fun close() {
        if (!retainingExecution || !live()) closeNow()
    }

    private fun closeNow() {
        if (!closed.compareAndSet(false, true)) return
        val failures = mutableListOf<java.io.IOException>()

        fun closeResource(resource: () -> Unit) {
            try {
                resource()
            } catch (error: java.io.IOException) {
                failures.add(error)
            }
        }
        closeResource { server.close() }
        synchronized(clients) {
            clients.toList().forEach { socket -> closeResource { socket.close() } }
            clients.clear()
        }
        watch.shutdown()
        // Active gateway calls keep their own physical ownership and settle after cancellation.
        workers.shutdown()
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}

private fun readBridgeLine(input: java.io.InputStream): String {
    val bytes = java.io.ByteArrayOutputStream()
    while (bytes.size() <= ScriptBridgeProtocol.MAX_BYTES) {
        val value = input.read()
        require(value >= 0) { "BRIDGE_REQUEST_INCOMPLETE" }
        if (value == 10) return bytes.toString(Charsets.UTF_8.name())
        bytes.write(value)
    }
    error("BRIDGE_REQUEST_TOO_LARGE")
}

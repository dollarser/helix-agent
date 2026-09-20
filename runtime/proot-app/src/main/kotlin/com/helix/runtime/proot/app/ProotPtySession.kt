package com.helix.runtime.proot.app

import android.os.SystemClock
import com.helix.runtime.proot.core.PtyInputConnection
import com.helix.runtime.proot.core.PtyOutputBuffer
import com.helix.runtime.proot.core.PtyProcessIdentity
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.core.PtySessionStore
import java.io.File
import java.util.concurrent.Executors

/** Runtime session worker. Its caller owns foreground lifetime and host execution admission. */
internal class ProotPtySession(
    initial: PtySessionRecord,
    private val store: PtySessionStore,
    private val launch: () -> ProotPtyProcess,
) {
    private val input = PtyInputConnection()
    val output = PtyOutputBuffer(initial.origin.sessionId, initial.origin.generation)
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile var record: PtySessionRecord = initial
        private set

    @Volatile private var size: Pair<Int, Int>? = null

    init {
        require(initial == PtySessionRecord(initial.origin))
        check(store.compareAndSet(null, initial)) { "PTY session already exists" }
    }

    /** Exactly once, after the service has acquired its lifetime and execution ownership. */
    fun start(allowLaunch: Boolean = true) {
        synchronized(lock) {
            check(!started) { "PTY worker already submitted" }
            started = true
            worker.execute { run(allowLaunch) }
            worker.shutdown()
        }
    }

    private var started = false

    fun attach(): String? = input.attach()

    fun detach(connection: String): Boolean = input.detach(connection)

    fun write(
        connection: String,
        bytes: ByteArray,
    ): PtyInputConnection.Admission = input.offer(connection, bytes)

    fun resize(
        rows: Int,
        columns: Int,
    ) {
        require(rows in 1..512 && columns in 1..512)
        size = rows to columns
    }

    fun stop(reason: PtySessionRecord.StopReason) {
        synchronized(lock) {
            update(record.requestStop(reason))
            input.close()
        }
    }

    private fun update(next: PtySessionRecord) {
        if (record != next) {
            check(store.compareAndSet(record, next)) { "PTY journal changed outside its owner" }
            record = next
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun run(allowLaunch: Boolean) {
        var process: ProotPtyProcess? = null
        try {
            synchronized(lock) {
                if (SystemClock.elapsedRealtime() >= record.origin.deadlineElapsedMs) {
                    update(record.requestStop(PtySessionRecord.StopReason.LEASE_EXPIRED))
                }
                if (!allowLaunch || record.phase == PtySessionRecord.Phase.CLOSING) {
                    update(record.neverStarted())
                    return
                }
            }
            process = launch()
            val identity = processIdentity(process.pid)
            synchronized(lock) { update(record.started(identity)) }
            pump(process)
        } catch (failure: Exception) {
            android.util.Log.e("ProotPtySession", "PTY worker failed", failure)
            synchronized(lock) {
                // A launcher exception does not necessarily prove that no child was forked.
                update(record.runtimeLost())
            }
        } finally {
            input.close()
            output.finish()
            process?.let(::cleanup)
        }
    }

    private fun pump(process: ProotPtyProcess) {
        val bytes = ByteArray(PtyOutputBuffer.CHUNK_BYTES)
        val writer = PtyPendingWrite(input)
        var quitSent = false
        var eof = false
        var closingAt: Long? = null
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (now >= record.origin.deadlineElapsedMs) stop(PtySessionRecord.StopReason.LEASE_EXPIRED)
            if (record.phase == PtySessionRecord.Phase.CLOSING) {
                writer.discard()
                if (closingAt == null) closingAt = now
                if (!quitSent && prootQuitHandlerReady(process.pid)) {
                    process.requestProotExit()
                    quitSent = true
                }
                if (now - closingAt >= CLOSE_TIMEOUT_MS) error("PTY close did not produce an exit receipt")
            } else {
                writer.flush(process)
            }
            size?.let { dimensions ->
                process.resize(dimensions.first, dimensions.second)
                if (size === dimensions) size = null
            }
            if (!eof) {
                val count = process.read(bytes)
                if (count < 0) eof = true else output.append(bytes, count)
            } else {
                Thread.sleep(20)
            }
            val exit = process.pollExit()
            if (exit != null) {
                drainPtyTail(process, output, eof)
                synchronized(lock) {
                    // Only ordinary exit through the pinned --kill-on-exit PRoot event loop is proof.
                    update(if (exit in 0..255) record.stoppedTree(exit) else record.runtimeLost())
                }
                process.reap()
                return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun cleanup(process: ProotPtyProcess) {
        try {
            if (process.pollExit() == null) process.killInitialGroup()
            val until = SystemClock.elapsedRealtime() + CLOSE_TIMEOUT_MS
            while (process.pollExit() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            if (process.pollExit() != null) process.reap()
        } catch (failure: Exception) {
            android.util.Log.e("ProotPtySession", "PTY cleanup remains uncertain", failure)
        } finally {
            process.closeMaster()
        }
    }

    companion object {
        private const val CLOSE_TIMEOUT_MS = 5000L
    }
}

private fun processIdentity(pid: Int): PtyProcessIdentity {
    val stat = File("/proc/$pid/stat").readText()
    check(stat.length <= 4096 && stat.startsWith("$pid ("))
    val fields = stat.substringAfterLast(") ").trim().split(' ')
    return PtyProcessIdentity(pid, fields[19].toLong())
}

/** The native child clears inherited handlers; SIGQUIT may be sent only after PRoot installs its own. */
private fun prootQuitHandlerReady(pid: Int): Boolean {
    val status = File("/proc/$pid/status").readText()
    check(status.length <= 16384)
    val caught =
        status
            .lineSequence()
            .first { it.startsWith("SigCgt:") }
            .substringAfter(':')
            .trim()
            .toULong(16)
    return caught and 4uL != 0uL
}

/** At most one bounded input chunk outside the queue; retain its suffix after a partial write. */
private class PtyPendingWrite(
    private val input: PtyInputConnection,
) {
    private var pending: ByteArray? = null
    private var offset = 0

    fun discard() {
        pending = null
    }

    fun flush(process: ProotPtyProcess) {
        if (pending == null) {
            pending = input.poll()
            offset = 0
        }
        pending?.let {
            offset += process.write(it, offset, it.size - offset)
            if (offset == it.size) pending = null
        }
    }
}

private fun drainPtyTail(
    process: ProotPtyProcess,
    output: PtyOutputBuffer,
    alreadyEnded: Boolean,
) {
    if (alreadyEnded) return
    val bytes = ByteArray(PtyOutputBuffer.CHUNK_BYTES)
    val deadline = SystemClock.elapsedRealtime() + 5000L
    while (SystemClock.elapsedRealtime() < deadline) {
        val count = process.read(bytes)
        if (count < 0) return
        output.append(bytes, count)
    }
    error("PTY output did not reach EOF after tracer exit")
}

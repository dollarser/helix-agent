package com.helix.runtime.proot.app

import android.app.Application
import android.os.Looper

/**
 * Private Runtime I/O primitive, not the session owner. All descriptor use/close and wait/signal
 * calls serialize here so a closed FD or reaped PID cannot be accidentally reused. Closing the
 * master does not settle execution; even a reaped leader is not proof of absent background jobs.
 */
internal class ProotPtyProcess private constructor(
    val pid: Int,
    private var master: Int,
) {
    private var exit: Int? = null
    private var reaped = false

    @Synchronized
    fun read(target: ByteArray): Int = ProotPtyNative.read(openMaster(), target)

    @Synchronized
    fun write(
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Int = ProotPtyNative.write(openMaster(), source, offset, length)

    @Synchronized
    fun resize(
        rows: Int,
        columns: Int,
    ) = ProotPtyNative.resize(openMaster(), rows, columns)

    @Synchronized
    fun foregroundGroup(): Int = ProotPtyNative.foregroundGroup(openMaster())

    /** Observe without reaping: keep the PID reserved until session reconciliation calls reap(). */
    @Synchronized
    fun pollExit(): Int? {
        requireWorker()
        if (exit == null) exit = ProotPtyNative.waitExit(pid).takeIf { it >= 0 }
        return exit
    }

    /** Idempotently reap only an observed exit. This alone never releases the execution owner. */
    @Synchronized
    fun reap(): Int {
        requireWorker()
        val observed = checkNotNull(exit) { "Observe PTY exit before reaping" }
        if (!reaped) {
            val actual = ProotPtyNative.reap(pid)
            if (actual >= 0) reaped = true
            check(actual == observed) { "PTY exit is not ready to reap" }
        }
        return observed
    }

    /** Escalation primitive only. Interactive jobs in other groups need separate reconciliation. */
    @Synchronized
    fun killInitialGroup() {
        requireWorker()
        check(!reaped) { "PTY leader already reaped" }
        ProotPtyNative.killInitialGroup(pid)
    }

    /** Ask a ready PRoot tracer to stop its tracees; caller must still observe and reconcile exit. */
    @Synchronized
    fun requestProotExit() {
        requireWorker()
        check(!reaped) { "PTY leader already reaped" }
        ProotPtyNative.requestProotExit(pid)
    }

    @Synchronized
    fun closeMaster() {
        requireWorker()
        if (master >= 0) {
            val descriptor = master
            master = -1
            ProotPtyNative.closeMaster(descriptor)
        }
    }

    private fun openMaster(): Int {
        requireWorker()
        check(master >= 0) { "PTY master already closed" }
        return master
    }

    companion object {
        private const val MAX_ENTRIES = 128
        private const val MAX_STRING_BYTES = 4096
        private const val MAX_VECTOR_BYTES = 65536
        private val environmentKey = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /** Fork success only; failed exec is observable as output plus exit 127, never a fake ready shell. */
        fun spawn(
            argv: List<String>,
            environment: Map<String, String>,
            rows: Int = 24,
            columns: Int = 80,
        ): ProotPtyProcess {
            requireWorker()
            check(Application.getProcessName().endsWith(":proot")) { "PTY requires the private Runtime process" }
            require(argv.isNotEmpty() && argv.first().startsWith('/'))
            require(environment.size <= MAX_ENTRIES)
            require(
                environment.all { (key, value) ->
                    key.length <= MAX_STRING_BYTES && value.length <= MAX_STRING_BYTES && key.matches(environmentKey)
                },
            )
            val args = encode(argv)
            val variables = encode(environment.map { (key, value) -> "$key=$value" })
            val result = ProotPtyNative.spawn(args, variables, rows, columns)
            return ProotPtyProcess(result[0], result[1])
        }

        private fun encode(values: List<String>): Array<ByteArray> {
            require(values.size <= MAX_ENTRIES)
            var total = 0
            val encoded =
                values.map {
                    require(it.length <= MAX_STRING_BYTES && '\u0000' !in it)
                    it.toByteArray(Charsets.UTF_8).also { bytes ->
                        require(bytes.size <= MAX_STRING_BYTES && bytes.size <= MAX_VECTOR_BYTES - total)
                        total += bytes.size
                    }
                }
            return encoded.toTypedArray()
        }

        private fun requireWorker() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "PTY I/O must run off the main thread" }
        }
    }
}

internal object ProotPtyNative {
    init {
        System.loadLibrary("proot_native")
    }

    external fun spawn(
        argv: Array<ByteArray>,
        environment: Array<ByteArray>,
        rows: Int,
        columns: Int,
    ): IntArray

    external fun read(
        fd: Int,
        target: ByteArray,
    ): Int

    external fun write(
        fd: Int,
        source: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    external fun resize(
        fd: Int,
        rows: Int,
        columns: Int,
    )

    external fun waitExit(pid: Int): Int

    external fun reap(pid: Int): Int

    external fun killInitialGroup(pid: Int)

    external fun requestProotExit(pid: Int)

    external fun foregroundGroup(fd: Int): Int

    external fun closeMaster(fd: Int)
}

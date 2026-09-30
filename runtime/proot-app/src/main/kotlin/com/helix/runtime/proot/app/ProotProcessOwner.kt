package com.helix.runtime.proot.app

import java.util.concurrent.TimeUnit

/** Own the Process immediately on creation, before PID discovery, stream pumps, or watchdog setup. */
internal class ProotProcessOwner(
    private val stopGroup: (Int) -> Unit,
) {
    @Volatile private var process: Process? = null

    @Volatile var pid: Int? = null

    fun start(launch: () -> Process): Process {
        check(process == null)
        return launch().also { process = it }
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun exitCode(): Int? = process?.takeUnless { it.isAlive }?.exitValue()

    fun awaitExit() {
        val original = process ?: return
        ProotExecutionExit.awaitExit(
            original::isAlive,
            { stop(original) },
            { original.waitFor(1000, TimeUnit.MILLISECONDS) },
        )
        runCatching { original.outputStream.close() }
        runCatching { original.inputStream.close() }
        runCatching { original.errorStream.close() }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A failed stop never grants exit; keep waiting.
    private fun stop(original: Process) {
        try {
            pid?.let(stopGroup)
        } catch (_: Exception) {
            // Process fallback still runs.
        }
        try {
            original.destroyForcibly()
        } catch (_: Exception) {
            // Actual liveness remains authoritative.
        }
    }
}

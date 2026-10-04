package com.helix.app.automation.shizuku

import android.os.IBinder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/** Fixed commands only. XML stays in this process and is deleted after observation. */
internal class ShizukuUiCommands(
    private val guard: IBinder,
    private val observation: ShizukuHierarchy,
) {
    fun clickMatch(selector: ShizukuUiSelector): String {
        val status =
            run {
                check(ShizukuExecutionGuard.check(guard)) { "GRANT_LOST" }
                ShizukuClickOperation(
                    hierarchy = observation::read,
                    allowed = { x, y, rotation ->
                        ShizukuExecutionGuard.check(guard, x, y, rotation) &&
                            (
                                (x == -1 && y == -1 && rotation == -1) ||
                                    observation.permitsPoint(selector, x, y, rotation)
                            )
                    },
                    tap = { x, y -> command(boundedTouchPressCommand(x, y), 5) == 0 },
                ).execute(selector)
            }
        return buildJsonObject { put("status", status) }.toString()
    }

    private fun command(
        arguments: List<String>,
        timeoutSeconds: Long,
    ): Int {
        val output = File.createTempFile("helix-ui-", ".log", File("/data/local/tmp"))
        var process: java.lang.Process? = null
        return try {
            check(ShizukuExecutionGuard.check(guard)) { "GRANT_LOST" }
            process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(output).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline ||
                    !ShizukuExecutionGuard.check(guard, finishing = true)
                ) {
                    return TIMEOUT_EXIT
                }
            }
            process.exitValue()
        } finally {
            process?.let {
                if (it.isAlive) {
                    it.destroyForcibly()
                    check(it.waitFor(5, TimeUnit.SECONDS)) { "COMMAND_TERMINATION_UNCONFIRMED" }
                }
            }
            output.delete()
        }
    }

    private companion object {
        const val TIMEOUT_EXIT = -2
    }
}

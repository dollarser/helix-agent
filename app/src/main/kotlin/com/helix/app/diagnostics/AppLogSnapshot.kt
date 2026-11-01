package com.helix.app.diagnostics

import android.os.Process
import com.helix.app.application.diagnostics.DiagnosticRedaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppLogSnapshot(
    val text: String,
    val truncated: Boolean,
)

/** User-triggered snapshot of this PID only, without READ_LOGS, root or a shell. Never persisted. */
internal object AppLogReader {
    suspend fun read(): AppLogSnapshot =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val process =
                    ProcessBuilder("logcat", "-d", "-t", "500", "--pid=${Process.myPid()}", "-v", "brief")
                        .redirectErrorStream(true)
                        .start()
                val reader =
                    async(Dispatchers.IO) {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var truncated = false
                        try {
                            process.inputStream.use { input ->
                                while (!truncated) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    val remaining = MAX_BYTES - output.size()
                                    output.write(buffer, 0, minOf(count, remaining))
                                    truncated = count > remaining || output.size() == MAX_BYTES
                                }
                            }
                            val text = output.toString(Charsets.UTF_8.name())
                            AppLogSnapshot(DiagnosticRedaction.redact(text), truncated)
                        } catch (failure: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw failure
                        }
                    }
                try {
                    val result = withTimeout(4000) { reader.await() }
                    withContext(Dispatchers.IO) {
                        if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) {
                            if (!result.truncated) throw IOException("APP_LOG_READ_FAILED")
                        }
                    }
                    result
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        reader.cancel()
                        process.destroyForcibly()
                        process.inputStream.close()
                        process.errorStream.close()
                        reader.join()
                        process.waitFor()
                    }
                }
            }
        }

    private const val MAX_BYTES = 256 * 1024
}

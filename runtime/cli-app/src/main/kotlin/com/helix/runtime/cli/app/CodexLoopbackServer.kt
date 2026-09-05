package com.helix.runtime.cli.app

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class CodexLoopbackServer private constructor(
    private val socket: ServerSocket,
    private val timeoutMillis: Int,
) : Closeable {
    val port: Int = socket.localPort
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun await(
        expectedState: String,
        callback: (CodexCallbackResult) -> Unit,
    ) {
        executor.execute {
            val result =
                try {
                    socket.soTimeout = timeoutMillis
                    var parsed: CodexCallbackResult = CodexCallbackResult.Ignored
                    while (!closed.get() && parsed is CodexCallbackResult.Ignored) {
                        socket.accept().use { client ->
                            parsed =
                                readTarget(client.getInputStream().buffered()).let {
                                    CodexOAuthProtocol.parseCallback(it, expectedState)
                                }
                            respond(client, parsed)
                        }
                    }
                    parsed
                } catch (_: SocketTimeoutException) {
                    CodexCallbackResult.Rejected("login timed out")
                } catch (error: IOException) {
                    if (closed.get()) {
                        CodexCallbackResult.Rejected("login cancelled")
                    } else {
                        CodexCallbackResult.Rejected("loopback callback failed: ${error.javaClass.simpleName}")
                    }
                } catch (error: IllegalArgumentException) {
                    CodexCallbackResult.Rejected("loopback callback rejected: ${error.message.orEmpty().take(128)}")
                } finally {
                    close()
                }
            callback(result)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            socket.close()
            executor.shutdownNow()
        }
    }

    private fun readTarget(input: BufferedInputStream): String {
        val line = StringBuilder()
        while (line.length <= MAX_REQUEST_LINE_CHARS) {
            val next = input.read()
            if (next == -1 || next == '\n'.code) break
            if (next != '\r'.code) line.append(next.toChar())
        }
        require(line.length <= MAX_REQUEST_LINE_CHARS) { "request line is too large" }
        val parts = line.toString().split(' ')
        require(parts.size == 3 && parts[0] == "GET" && parts[2].startsWith("HTTP/1.")) {
            "invalid callback request line"
        }
        return parts[1]
    }

    private fun respond(
        client: java.net.Socket,
        result: CodexCallbackResult,
    ) {
        val success = result is CodexCallbackResult.Code
        val body = if (success) SUCCESS_PAGE else FAILURE_PAGE
        val status = if (result is CodexCallbackResult.Ignored) "404 Not Found" else "200 OK"
        client.getOutputStream().bufferedWriter(Charsets.UTF_8).use { output ->
            output.write("HTTP/1.1 $status\r\n")
            output.write("Content-Type: text/html; charset=utf-8\r\n")
            output.write("Cache-Control: no-store\r\n")
            output.write("Content-Length: ${body.encodeToByteArray().size}\r\n")
            output.write("Connection: close\r\n\r\n")
            output.write(body)
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 180_000
        private const val MAX_REQUEST_LINE_CHARS = 8 * 1024
        private const val SUCCESS_PAGE =
            "<!doctype html><title>Helix login complete</title><p>Return to Helix CLI Runtime.</p>"
        private const val FAILURE_PAGE =
            "<!doctype html><title>Helix login failed</title><p>Return to Helix CLI Runtime.</p>"

        fun bind(timeoutMillis: Int = TIMEOUT_MILLIS): CodexLoopbackServer {
            require(timeoutMillis in 1..TIMEOUT_MILLIS)
            var last: Exception? = null
            for (port in intArrayOf(1455, 1457)) {
                try {
                    return CodexLoopbackServer(ServerSocket(port, 4), timeoutMillis)
                } catch (error: IOException) {
                    last = error
                }
            }
            throw IllegalStateException("Codex callback ports 1455 and 1457 are unavailable", last)
        }
    }
}

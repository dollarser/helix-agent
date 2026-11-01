package com.helix.app.proot

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import com.helix.app.files.ManualFileBackend
import com.helix.core.workspace.FileScopePath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only proof of directory RPC. Not a production endpoint, filesystem mount or permission grant. */
internal class SafRpcPrototype(
    private val backend: ManualFileBackend,
    private val checkGrant: () -> Unit,
) : AutoCloseable {
    val name = "helix-saf-${UUID.randomUUID()}"
    val nonce = UUID.randomUUID().toString()
    private val closed = AtomicBoolean()
    private val server = LocalServerSocket(name)

    @Volatile private var socket: LocalSocket? = null
    private val deadline = System.nanoTime() + 60_000_000_000L
    private val worker = Thread({ serve() }, "saf-rpc-fixture").apply { start() }

    private fun serve() {
        try {
            repeat(32) {
                if (closed.get() || System.nanoTime() >= deadline) return
                server.accept().use { client ->
                    socket = client
                    client.soTimeout = 5000
                    val response =
                        try {
                            require(client.peerCredentials.uid == Process.myUid())
                            handle(readLine(client))
                        } catch (_: IllegalArgumentException) {
                            status("invalid_or_unsupported")
                        } catch (_: Exception) {
                            status("unavailable_or_changed")
                        }
                    val bytes = (response.toString() + "\n").toByteArray()
                    require(bytes.size <= 262144)
                    client.outputStream.write(bytes)
                    client.outputStream.flush()
                }
                socket = null
            }
        } catch (failure: java.io.IOException) {
            check(closed.get()) { "Unexpected prototype socket failure: ${failure.javaClass.simpleName}" }
        } finally {
            server.close()
        }
    }

    private fun readLine(client: LocalSocket): JsonObject {
        val line = java.io.ByteArrayOutputStream()
        while (line.size() <= 8192) {
            val next = client.inputStream.read()
            require(next >= 0) { "Incomplete request" }
            if (next == 10) return Json.parseToJsonElement(line.toString("UTF-8")) as JsonObject
            line.write(next)
        }
        error("Request limit")
    }

    private fun handle(request: JsonObject): JsonObject {
        require(request.keys.all { it in setOf("nonce", "operation", "path", "expectedHash") })
        require(
            MessageDigest.isEqual(
                nonce.toByteArray(),
                request
                    .getValue("nonce")
                    .jsonPrimitive.content
                    .toByteArray(),
            ),
        )
        check(!closed.get() && System.nanoTime() < deadline)
        checkGrant()
        val operation = request.getValue("operation").jsonPrimitive.content
        if (operation !in setOf("stat", "ls", "read")) return status("unsupported_posix_or_write")
        val path = FileScopePath("saf-fixture", request.getValue("path").jsonPrimitive.content).relativePath
        val info = requireNotNull(backend.stat(path))
        return when (operation) {
            "stat" -> {
                buildJsonObject {
                    put("status", "ok")
                    put("directory", info.directory)
                    put("size", info.size)
                    put("posix", false)
                }
            }

            "ls" -> {
                require(info.directory)
                val names = backend.children(path)
                require(names.size <= 256)
                buildJsonObject {
                    put("status", "ok")
                    put("entries", kotlinx.serialization.json.JsonArray(names.map(::JsonPrimitive)))
                    put("posix", false)
                }
            }

            "read" -> {
                require(!info.directory)
                val bytes =
                    backend.read(path).use { input ->
                        com.helix.app.files.ImportFileCapture
                            .read(input, 128 * 1024L)
                    }
                checkGrant()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                request["expectedHash"]?.let { check(it.jsonPrimitive.content == hash) { "Source changed" } }
                buildJsonObject {
                    put("status", "ok")
                    put("hash", hash)
                    put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    put("posix", false)
                }
            }

            else -> {
                status("unsupported_posix_or_write")
            }
        }
    }

    private fun status(value: String) =
        buildJsonObject {
            put("status", value)
            put("posix", false)
        }

    private fun shutdown(fd: java.io.FileDescriptor?) {
        if (fd == null) return
        try {
            android.system.Os.shutdown(fd, android.system.OsConstants.SHUT_RDWR)
        } catch (_: android.system.ErrnoException) {
            check(closed.get()) // Concurrent close/peer exit is expected only during owner teardown.
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            worker.join(5000)
            check(!worker.isAlive) { "Prototype executor still running" }
            return
        }
        socket?.let { active ->
            shutdown(active.fileDescriptor)
            active.close()
        }
        shutdown(server.fileDescriptor)
        server.close()
        worker.join(5000)
        check(!worker.isAlive) { "Prototype executor still running" }
    }
}

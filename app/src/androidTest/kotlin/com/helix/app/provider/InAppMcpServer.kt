package com.helix.app.provider

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EV-04 (U4) combined-soak fixture: an in-APK deterministic MCP server on a real `127.0.0.1`
 * socket serving real MCP JSON-RPC (`POST /mcp`), so the REAL `McpAppService` handshake
 * (`testConnection` -> initialize/tools-list) + `McpDynamicToolBridge` + the production
 * dispatcher execute a DETERMINISTIC read against a local server (master plan:58 "固定本地协议服务
 * 提供模型输出，标明不是模型能力评测"). It is a NEW file; the socket plumbing mirrors
 * [ScriptedTaskModelServer] (real `ServerSocket` + byte-level head parse + `Connection: close`
 * HTTP/1.1). The JSON-RPC response shapes mirror [scripts/hxa100_mcp_fixture.py] (the proven
 * host fixture the real client already accepts).
 *
 * It exposes exactly ONE read-only-looking tool, `fixture_read`, whose `tools/call` returns the
 * fixed text `SYNTHETIC_READ_OK`. The tool is registered by the fixture under a single-segment
 * server id, so the production `McpToolSource` names it `mcp.<serverId>.fixture_read` — the exact
 * tool name the [ScriptedTaskModelServer] is armed to emit. The server never reads a credential
 * and the fixture never logs the request body beyond the JSON-RPC `method`.
 */
internal class InAppMcpServer : AutoCloseable {
    private val serverSocket: ServerSocket =
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    private val running = AtomicBoolean(true)
    private val thread: Thread =
        Thread(
            {
                acceptLoop()
            },
            "helix-ev04-mcp",
        ).also { it.isDaemon = true }

    val port: Int
        get() = serverSocket.localPort

    fun start() {
        thread.start()
    }

    @Suppress("SwallowedException") // a close-on-close is the intended no-op
    override fun close() {
        running.set(false)
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    @Suppress("SwallowedException") // a per-connection failure drops that exchange only; the handshake/turn maps it
    private fun acceptLoop() {
        while (running.get()) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    break // server socket closed (fixture stop)
                }
            try {
                handle(socket)
            } catch (e: Exception) {
                // A per-connection failure drops that exchange only; the handshake/turn maps it.
            } finally {
                try {
                    socket.close()
                } catch (e: IOException) {
                    // already closed
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val head = readUntilHead(input)
        val lines = head.split("\r\n")
        val path =
            lines
                .firstOrNull()
                .orEmpty()
                .split(" ")
                .getOrNull(1)
                .orEmpty()
        var contentLength = 0
        for (line in lines.drop(1)) {
            if (line.lowercase().startsWith("content-length:")) {
                contentLength = line.substringAfter(":").trim().toInt()
            }
        }
        val body =
            if (contentLength > 0) {
                ByteArray(contentLength).also { readFully(input, it) }
            } else {
                ByteArray(0)
            }
        respond(output, path, String(body, StandardCharsets.UTF_8))
    }

    private fun respond(
        output: OutputStream,
        path: String,
        requestBody: String,
    ) {
        if (path != "/mcp") {
            writeResponse(output, 404, "application/json", "{\"error\":\"not found\"}")
            return
        }
        val request = JSONObject(requestBody)
        val method = request.optString("method", "")
        if (!request.has("id")) {
            // A notification (e.g. `notifications/initialized`) gets a 202 with no body.
            writeResponse(output, 202, "application/json", "")
            return
        }
        val id = request.opt("id")
        val result = respond(method, request.optJSONObject("params") ?: JSONObject())
        val envelope =
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
        writeResponse(output, 200, "application/json", envelope.toString())
    }

    @Suppress("UnusedParameter", "UseCheckOrError") // params unused; a bad request is IllegalStateException
    private fun respond(
        method: String,
        params: JSONObject,
    ): Any =
        when (method) {
            "initialize" -> {
                JSONObject()
                    .put("protocolVersion", "2025-03-26")
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "helix-ev04-fixture").put("version", "1"),
                    )
            }

            "tools/list" -> {
                JSONObject()
                    .put(
                        "tools",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("name", TOOL_NAME)
                                    .put("description", "Read synthetic fixture data.")
                                    .put(
                                        "inputSchema",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject().put("case", JSONObject().put("type", "string")),
                                            ).put("required", JSONArray().put("case"))
                                            .put("additionalProperties", false),
                                    ).put(
                                        "annotations",
                                        JSONObject()
                                            .put("readOnlyHint", true)
                                            .put("destructiveHint", false),
                                    ),
                            ),
                    )
            }

            "tools/call" -> {
                JSONObject()
                    .put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", READ_TEXT)),
                    ).put("isError", false)
            }

            "resources/list" -> {
                JSONObject().put("resources", JSONArray())
            }

            "prompts/list" -> {
                JSONObject().put("prompts", JSONArray())
            }

            "ping" -> {
                JSONObject()
            }

            else -> {
                throw IllegalStateException("unexpected MCP method: $method")
            }
        }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 $status ${reason(status)}\r\n" +
                    "Server: helix-fixture\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            ).toByteArray(StandardCharsets.UTF_8),
        )
        output.write(bytes)
        output.flush()
    }

    /** Reads the request head up to and including the CRLFCRLF separator (requests are small). */
    private fun readUntilHead(input: InputStream): String {
        val sb = StringBuilder()
        val tail = ArrayList<Byte>(HEAD_END.size)
        var done = false
        while (!done) {
            val b = input.read()
            if (b < 0) {
                done = true // EOF before the separator: return what we have
            } else {
                sb.append(b.toChar())
                tail.add(b.toByte())
                while (tail.size > HEAD_END.size) {
                    tail.removeAt(0)
                }
                done = tailMatchesHeadEnd(tail)
            }
        }
        return sb.toString()
    }

    /** True when [tail] holds the exact CRLFCRLF head terminator. */
    private fun tailMatchesHeadEnd(tail: List<Byte>): Boolean =
        tail.size == HEAD_END.size && tail.indices.all { i -> tail[i] == HEAD_END[i] }

    private fun readFully(
        input: InputStream,
        buf: ByteArray,
    ) {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read < 0) break
            offset += read
        }
    }

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            202 -> "Accepted"
            404 -> "Not Found"
            else -> "Status"
        }

    companion object {
        /** The single tool the fixture advertises (see [respond]). */
        const val TOOL_NAME: String = "fixture_read"

        /** The fixed read text a `tools/call` returns (the fixture's deterministic "file" content). */
        const val READ_TEXT: String = "SYNTHETIC_READ_OK"

        /** The CRLFCRLF request-head terminator. */
        private val HEAD_END: ByteArray = "\r\n\r\n".encodeToByteArray()
    }
}

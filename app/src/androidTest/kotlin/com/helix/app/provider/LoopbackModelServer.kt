package com.helix.app.provider

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HXA-059 device fixture: an in-APK loopback HTTP server (127.0.0.1, random high
 * port) that answers the FIVE production probe phases end-to-end — real OkHttp
 * wire, real OpenAI-compatible/Anthropic SSE decoders, no wire seam. The
 * response bodies are the same fixture shapes as
 * [com.helix.app.chat.textAnswerStream]/[com.helix.app.chat.toolCallStream]
 * (HXA-056), so the streams are deterministic offline model replies.
 *
 * The model ids are SHORT fixture strings (`fixture-model-*`): the fixture
 * never echoes machine paths or host-specific data, and the request bodies are
 * discarded (the credential is never read, logged or stored).
 *
 * Threading: one accept thread; each connection is handled SEQUENTIALLY in the
 * accept thread, which preserves the probe's phase order (phase N completes
 * before phase N+1 starts) — the [Mode.OPENAI_PHASE2_AUTH] state machine
 * (first models call 200, later ones 401) relies on that order.
 */
internal class LoopbackModelServer(
    private val mode: Mode,
) : AutoCloseable {
    /** The scripted backend behavior. */
    enum class Mode {
        /** OpenAI-compatible: `GET /v1/models` always 200 (3 ids); `POST /v1/chat/completions` → SSE. */
        OPENAI_LISTED,

        /** OpenAI-compatible: FIRST `GET /v1/models` 200 (phase 1 passes), later ones 401 (phase 2 Failed/AUTH). */
        OPENAI_PHASE2_AUTH,

        /** OpenAI-compatible: `GET /v1/models` 200 with 300 ids (the UI display-cap case). */
        OPENAI_LARGE,

        /**
         * Anthropic: NO model-list endpoint (phase 2 = Unsupported);
         * `POST /v1/messages` → SSE (phase 1 validates by stream).
         */
        ANTHROPIC_UNSUPPORTED,
    }

    private val serverSocket: ServerSocket =
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    private val running = AtomicBoolean(true)
    private val modelsCalls = AtomicInteger(0)
    private val thread: Thread =
        Thread(
            {
                acceptLoop()
            },
            "helix-fixture-http",
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

    @Suppress("SwallowedException") // per-connection failures are handled below; the probe owns the failure mapping
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
                // A per-connection failure drops that exchange only; the probe
                // maps it to its own failure class (or the test fails).
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
        when (mode) {
            Mode.OPENAI_LISTED,
            Mode.OPENAI_LARGE,
            -> {
                when (path) {
                    "/v1/models" -> writeJson(output, 200, modelsBody())
                    "/v1/chat/completions" -> writeSse(output, chatStream(requestBody))
                    else -> writeJson(output, 404, "{\"error\":\"not found\"}")
                }
            }

            Mode.OPENAI_PHASE2_AUTH -> {
                when (path) {
                    "/v1/models" -> {
                        if (modelsCalls.getAndIncrement() == 0) {
                            writeJson(output, 200, modelsBody())
                        } else {
                            writeJson(output, 401, "{\"error\":\"invalid key\"}")
                        }
                    }

                    "/v1/chat/completions" -> {
                        writeSse(output, chatStream(requestBody))
                    }

                    else -> {
                        writeJson(output, 404, "{\"error\":\"not found\"}")
                    }
                }
            }

            Mode.ANTHROPIC_UNSUPPORTED -> {
                when (path) {
                    "/v1/messages" -> writeSse(output, anthropicStream(requestBody))

                    // This backend has no model-list endpoint; the Anthropic
                    // adapter never calls one (phase 2 = Unsupported).
                    else -> writeJson(output, 404, "{\"error\":\"not found\"}")
                }
            }
        }
    }

    private fun modelsBody(): String {
        val ids =
            if (mode == Mode.OPENAI_LARGE) {
                (0 until LARGE_MODEL_COUNT).map { "fixture-model-%03d".format(it) }
            } else {
                listOf("fixture-model-a", "fixture-model-b", "fixture-model-c")
            }
        val entries = ids.joinToString(",") { id -> "{\"id\":\"$id\",\"object\":\"model\"}" }
        return "{\"object\":\"list\",\"data\":[$entries]}"
    }

    /** The OpenAI-compatible stream for the request body (tool fixture iff the body offers tools). */
    private fun chatStream(requestBody: String): String =
        if (requestBody.contains("\"tools\"")) OPENAI_TOOL_STREAM else OPENAI_TEXT_STREAM

    /** The Anthropic Messages stream for the request body (tool fixture iff the body offers tools). */
    private fun anthropicStream(requestBody: String): String =
        if (requestBody.contains("\"tools\"")) ANTHROPIC_TOOL_STREAM else ANTHROPIC_TEXT_STREAM

    private fun writeJson(
        output: OutputStream,
        status: Int,
        body: String,
    ) {
        writeResponse(output, status, "application/json", body)
    }

    private fun writeSse(
        output: OutputStream,
        stream: String,
    ) {
        writeResponse(output, 200, "text/event-stream", stream)
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
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Status"
        }

    companion object {
        const val LARGE_MODEL_COUNT = 300

        /** The CRLFCRLF request-head terminator. */
        private val HEAD_END: ByteArray =
            "\r\n\r\n".encodeToByteArray()

        // --- fixture streams: the same shapes HXA-056's ScriptedSseWire replays ---

        /** A plain streaming text answer: role chunk, two content deltas, stop, usage, DONE. */
        private const val OPENAI_TEXT_STREAM =
            "data: {\"id\":\"chatcmpl-fix\",\"object\":\"chat.completion.chunk\"," +
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}," +
                "\"finish_reason\":null}]}\n\n" +
                "data: {\"id\":\"chatcmpl-fix\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"id\":\"chatcmpl-fix\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"id\":\"chatcmpl-fix\",\"choices\":[]," +
                "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n" +
                "data: [DONE]\n\n"

        /**
         * A tool-call answer (Ollama shape: the COMPLETE arguments ride in the start
         * fragment) — the probe's phase-4 requirement is a started+finished call
         * index with `finishReason = tool_calls`.
         */
        private const val OPENAI_TOOL_STREAM =
            "data: {\"id\":\"chatcmpl-fix\",\"object\":\"chat.completion.chunk\"," +
                "\"choices\":[{\"index\":0," +
                "\"delta\":{\"role\":\"assistant\",\"content\":\"\"," +
                "\"tool_calls\":[{\"id\":\"call_fix\",\"index\":0," +
                "\"type\":\"function\"," +
                "\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"probe\\\"}\"}}]}," +
                "\"finish_reason\":null}]}\n\n" +
                "data: {\"id\":\"chatcmpl-fix\",\"choices\":[{\"index\":0," +
                "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n"

        /** Anthropic Messages text answer: message_start → text block → end_turn → message_stop. */
        private const val ANTHROPIC_TEXT_STREAM =
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_fix\",\"type\":\"message\"," +
                "\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n" +
                "event: content_block_start\n" +
                "data: {\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n" +
                "event: content_block_stop\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null}," +
                "\"usage\":{\"output_tokens\":1}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n"

        /** Anthropic Messages tool answer: tool_use block with the complete arguments in one delta. */
        private const val ANTHROPIC_TOOL_STREAM =
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_fix\",\"type\":\"message\"," +
                "\"role\":\"assistant\",\"content\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n" +
                "event: content_block_start\n" +
                "data: {\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_fix\",\"name\":\"echo\"}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"text\\\":\\\"probe\\\"}\"}}\n\n" +
                "event: content_block_stop\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null}," +
                "\"usage\":{\"output_tokens\":1}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n"
    }
}

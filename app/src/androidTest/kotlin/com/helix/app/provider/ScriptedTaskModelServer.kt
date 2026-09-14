package com.helix.app.provider

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * EV-04 (U4) combined-soak fixture: an in-APK scripted model server on a real `127.0.0.1` socket
 * serving real OpenAI-compatible SSE, so the REAL `ProviderService`/`ChatService`/`ToolDispatcher`
 * execute a DETERMINISTIC sequence of tool calls (master plan:58 "固定本地协议服务提供模型输出，
 * 标明不是模型能力评测"). It is a NEW file; [LoopbackModelServer] is left untouched and its socket
 * plumbing (real `ServerSocket` + byte-level head parse + `Connection: close` HTTP/1.1) is mirrored
 * here so both can coexist.
 *
 * Two regimes, selected per request:
 *  - **disarmed** (the one-time `runConnectionTest` probe before any task): probe-compatible replies
 *    exactly like [LoopbackModelServer] — a plain text stream, or an `echo` tool-call stream when the
 *    request carries `tools` (the `CapabilityProbe`'s tool phase). The probe never sees a scripted
 *    task step.
 *  - **armed** (a task cycle): [arm] queues the scripted steps. Each model request in the turn returns
 *    the NEXT step as a tool-call SSE, chosen by counting the completed tool results already in the
 *    request body (`"tool_call_id"` occurrences — the OpenAI chat encoder re-sends every settled tool
 *    result as `{"role":"tool","tool_call_id":...}`, and the assistant `tool_calls` steps carry only
 *    `"id"`, so this count is exactly the number of steps finished so far). Once the queue is
 *    exhausted the server returns the final text (`finish_reason:"stop"`) to end the turn.
 *
 * A step's arguments are a **supplier** so the fixture can resolve app-generated values at request
 * time (e.g. a browser node token minted by the just-run `browser.snapshot`); fixed arguments are a
 * constant supplier. The server never reads the credential and never logs request secrets — the body
 * is scanned only for the step index. The model ids are short fixture strings.
 */
internal class ScriptedTaskModelServer : AutoCloseable {
    /**
     * One scripted assistant fragment: a single tool call. [args] returns the raw JSON-object text
     * (e.g. `{"path":"scope:app:output/x.txt","content":"hi"}`) evaluated at request time.
     */
    class Step(
        val toolName: String,
        val args: () -> String,
    )

    private val serverSocket: ServerSocket =
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    private val running = AtomicBoolean(true)
    private val thread: Thread =
        Thread(
            {
                acceptLoop()
            },
            "helix-ev04-scripted-model",
        ).also { it.isDaemon = true }

    val port: Int
        get() = serverSocket.localPort

    // -- armed task-cycle state (written by the test thread, read by the server thread) -----------
    @Volatile
    private var armed = false

    @Volatile
    private var armedSteps: List<Step> = emptyList()

    @Volatile
    private var armedFinalText = "Done."

    @Volatile
    private var finalResponseGate: CountDownLatch? = null

    @Volatile
    var finalResponseRequested = CountDownLatch(1)
        private set

    fun releaseFinalResponse() {
        finalResponseGate?.countDown()
    }

    /** The tool name emitted for the most recent armed request (for cycles.jsonl attribution). */
    val lastEmittedTool: AtomicLong = AtomicLong(0)
    private val callCounter = AtomicLong(0)

    fun start() {
        thread.start()
    }

    /**
     * Queue the scripted steps for the next task turn. MUST be called before the turn starts
     * (before `send`/`continueGoal`). An empty [steps] makes the model answer with [finalText]
     * immediately (a pure text turn). The server stays armed until [arm]ed again, so call this once
     * per task cycle.
     */
    fun arm(
        steps: List<Step>,
        finalText: String = "Done.",
        holdFinalResponse: Boolean = false,
    ) {
        releaseFinalResponse()
        finalResponseRequested = CountDownLatch(1)
        finalResponseGate = if (holdFinalResponse) CountDownLatch(1) else null
        armedSteps = steps
        armedFinalText = finalText
        armed = true
    }

    @Suppress("SwallowedException") // a close-on-close is the intended no-op
    override fun close() {
        running.set(false)
        releaseFinalResponse()
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // already closed
        }
    }

    @Suppress("SwallowedException") // per-connection failures are handled below; the probe owns the mapping
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
                // A per-connection failure drops that exchange only; the probe/turn maps it.
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
        val method =
            lines
                .firstOrNull()
                .orEmpty()
                .split(" ")
                .getOrNull(0)
                .orEmpty()
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
        respond(output, method, path, String(body, StandardCharsets.UTF_8))
    }

    @Suppress("UnusedParameter") // method not needed by the scripted model handler
    private fun respond(
        output: OutputStream,
        method: String,
        path: String,
        requestBody: String,
    ) {
        when (path) {
            // The probe's list-models phase.
            "/v1/models" -> writeJson(output, 200, modelsBody())

            // The probe's stream phase and every armed task turn.
            "/v1/chat/completions" -> writeSse(output, chatStream(requestBody))

            else -> writeJson(output, 404, "{\"error\":\"not found\"}")
        }
    }

    private fun modelsBody(): String =
        "{\"object\":\"list\",\"data\":[" +
            "{\"id\":\"$MODEL_ID\",\"object\":\"model\"}" +
            ",{\"id\":\"fixture-model-b\",\"object\":\"model\"}" +
            ",{\"id\":\"fixture-model-c\",\"object\":\"model\"}" +
            "]}"

    /** The OpenAI-compatible stream for the request body (disarmed = probe; armed = scripted step). */
    private fun chatStream(requestBody: String): String {
        if (!armed) {
            // Probe-compatible, identical to LoopbackModelServer.OPENAI_LISTED.
            return if (requestBody.contains("\"tools\"")) openaiEchoToolStream else openaiTextStream
        }
        val completed = countOccurrences(requestBody, TOOL_RESULT_MARKER)
        val steps = armedSteps
        return if (completed < steps.size) {
            val step = steps[completed]
            toolCallStream("call_ev04_" + callCounter.incrementAndGet(), step.toolName, step.args())
        } else {
            finalResponseRequested.countDown()
            val gate = finalResponseGate
            if (gate != null && !gate.await(30, TimeUnit.SECONDS)) {
                throw IOException("EV04 final response gate timed out")
            }
            textAnswerStream(armedFinalText)
        }
    }

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

    /**
     * A tool-call answer (Ollama shape: the COMPLETE arguments ride in the start fragment, followed
     * by `finish_reason:"tool_calls"`, then DONE). [argsJson] is a JSON object text embedded as the
     * `arguments` string value.
     */
    private fun toolCallStream(
        callId: String,
        name: String,
        argsJson: String,
    ): String =
        "data: {\"id\":\"chatcmpl-ev04\",\"object\":\"chat.completion.chunk\"," +
            "\"choices\":[{\"index\":0," +
            "\"delta\":{\"role\":\"assistant\",\"content\":\"\"," +
            "\"tool_calls\":[{\"id\":${jsonString(callId)},\"index\":0," +
            "\"type\":\"function\"," +
            "\"function\":{\"name\":${jsonString(name)},\"arguments\":${jsonString(argsJson)}}}]}," +
            "\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[{\"index\":0," +
            "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"

    /** A plain streaming text answer: role chunk, one content delta, stop, usage, DONE. */
    private fun textAnswerStream(text: String): String =
        "data: {\"id\":\"chatcmpl-ev04\",\"object\":\"chat.completion.chunk\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}," +
            "\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[{\"index\":0," +
            "\"delta\":{\"content\":${jsonString(text)}},\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[{\"index\":0," +
            "\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[]," +
            "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n" +
            "data: [DONE]\n\n"

    /** The `CapabilityProbe` tool phase: a started+finished `echo` call (LoopbackModelServer shape). */
    private val openaiEchoToolStream: String =
        "data: {\"id\":\"chatcmpl-ev04\",\"object\":\"chat.completion.chunk\"," +
            "\"choices\":[{\"index\":0," +
            "\"delta\":{\"role\":\"assistant\",\"content\":\"\"," +
            "\"tool_calls\":[{\"id\":\"call_fix\",\"index\":0," +
            "\"type\":\"function\"," +
            "\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"probe\\\"}\"}}]}," +
            "\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[{\"index\":0," +
            "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"

    private val openaiTextStream: String =
        "data: {\"id\":\"chatcmpl-ev04\",\"object\":\"chat.completion.chunk\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}," +
            "\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[{\"index\":0," +
            "\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[{\"index\":0," +
            "\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: {\"id\":\"chatcmpl-ev04\",\"choices\":[]," +
            "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n" +
            "data: [DONE]\n\n"

    /** Encodes [s] as a JSON string literal (surrounding quotes + escaping) for SSE embedding. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> {
                    sb.append("\\\"")
                }

                '\\' -> {
                    sb.append("\\\\")
                }

                '\n' -> {
                    sb.append("\\n")
                }

                '\r' -> {
                    sb.append("\\r")
                }

                '\t' -> {
                    sb.append("\\t")
                }

                '\b' -> {
                    sb.append("\\b")
                }

                '' -> {
                    sb.append("\\f")
                }

                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.append("\"").toString()
    }

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
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
        /** The model id the fixture registers providers for (also the first listed model). */
        const val MODEL_ID: String = "fixture-model-ev04"

        /** The OpenAI chat encoder's settled-tool-result marker (see chatStream's count). */
        private const val TOOL_RESULT_MARKER = "\"tool_call_id\""

        /** The CRLFCRLF request-head terminator. */
        private val HEAD_END: ByteArray =
            "\r\n\r\n".encodeToByteArray()
    }
}

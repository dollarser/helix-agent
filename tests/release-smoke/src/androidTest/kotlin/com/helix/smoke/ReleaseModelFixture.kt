package com.helix.smoke

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Deterministic loopback provider; every tool still enters the real R8 Dispatcher. */
internal class ReleaseModelFixture(
    private val fixturePackage: String,
) : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(3)
    val failure = AtomicReference<Throwable?>()
    val endpoint get() = "http://127.0.0.1:${server.localPort}/v1"

    @Volatile var oldToken: String? = null
    private val emitted = mutableSetOf<String>()

    init {
        workers.execute {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    workers.execute {
                        try {
                            socket.use(::handle)
                        } catch (error: Exception) {
                            if (!server.isClosed) failure.compareAndSet(null, error)
                        }
                    }
                }
            } catch (error: java.net.SocketException) {
                if (!server.isClosed) failure.compareAndSet(null, error)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val header = ByteArrayOutputStream()
        var tail = ""
        while (!tail.endsWith("\r\n\r\n")) {
            val next = input.read()
            check(next >= 0 && header.size() < 32_768)
            header.write(next)
            tail = (tail + next.toChar()).takeLast(4)
        }
        val lines = header.toString("UTF-8").lines()
        if (lines.first().startsWith("GET ")) {
            respond(socket, "application/json", "{\"data\":[{\"id\":\"r8-fixture\"}]}")
            return
        }
        val contentLength = lines.single { it.startsWith("Content-Length:", true) }
        val length = contentLength.substringAfter(':').trim().toInt()
        require(length in 1..2_097_152)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            check(count > 0)
            offset += count
        }
        val request = JSONObject(body.toString(Charsets.UTF_8))
        val response = model(request)
        val message = response.first
        val finish = response.second
        val usage = JSONObject().put("prompt_tokens", 100)
        usage.put("completion_tokens", 10).put("total_tokens", 110)
        if (!request.optBoolean("stream", false)) {
            val choice = JSONObject().put("index", 0).put("message", message)
            choice.put("finish_reason", finish)
            val responseBody =
                JSONObject()
                    .put("id", "fixture")
                    .put("choices", JSONArray().put(choice))
                    .put("usage", usage)
            respond(socket, "application/json", responseBody.toString())
            return
        }
        val chunks =
            listOf(
                chunk(message, JSONObject.NULL),
                chunk(JSONObject(), finish),
                JSONObject().put("id", "fixture").put("choices", JSONArray()).put("usage", usage),
            )
        val stream = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        respond(socket, "text/event-stream", stream)
    }

    @Synchronized private fun model(request: JSONObject): Pair<JSONObject, String> {
        val messages = request.optJSONArray("messages")?.objects().orEmpty()
        val user = messages.lastOrNull { it.optString("role") == "user" }
        val lastUser = user?.opt("content").toString()
        val phase = Regex("R8_BOUNDARY=([a-z-]+)").find(lastUser)?.groupValues?.get(1)
        val tools =
            request
                .optJSONArray("tools")
                ?.objects()
                .orEmpty()
                .map { it.getJSONObject("function").getString("name") }
        if (phase == null) {
            return if (tools == listOf("echo")) {
                call("probe", "echo", JSONObject().put("text", "probe"))
            } else {
                text("ok")
            }
        }
        val toolMessages = messages.filter { it.optString("role") == "tool" }
        val results = toolMessages.associateBy { it.optString("tool_call_id") }
        val step = steps(phase, results).firstOrNull { it.id !in results }
        return if (step != null) {
            if (step.tool !in tools) {
                check(step.id + "-search" !in results) { "Discovery did not expose ${step.tool}" }
                val query = JSONObject().put("query", step.tool)
                call(step.id + "-search", "tools.search", query)
            } else {
                check(emitted.add(step.id)) { "Unexpected replay of ${step.id}" }
                call(step.id, step.tool, step.arguments().put("__helix_intent", "Check ${step.id}"))
            }
        } else {
            if (phase == "stale-capture") {
                val observation = results.getValue("r8-stale-snapshot").opt("content")
                oldToken = checkNotNull(findNode(observation)).getString("token")
            }
            text("R8_DONE_$phase")
        }
    }

    private data class Step(
        val id: String,
        val tool: String,
        val arguments: () -> JSONObject,
    )

    private fun steps(
        phase: String,
        results: Map<String, JSONObject>,
    ): List<Step> {
        val launch =
            Step("r8-$phase-launch", "ui.launch") {
                JSONObject().put("packageName", fixturePackage)
            }
        val ready =
            Step("r8-$phase-ready", "ui.wait") {
                JSONObject()
                    .put("text", "R8 target button")
                    .put("timeoutMillis", 10_000)
                    .put("condition", "stable")
                    .put("stableMillis", 500)
            }
        results[ready.id]?.let(::requireStableWindow)
        return listOf(launch, ready) +
            when (phase) {
                "cancel" -> {
                    listOf(
                        Step("r8-cancel-wait", "ui.wait") {
                            JSONObject().put("text", "R8_NEVER_PRESENT").put("timeoutMillis", 60_000)
                        },
                    )
                }

                "unknown" -> {
                    listOf(
                        Step("r8-unknown-device", "ui.device") { JSONObject() },
                        Step("r8-unknown-gesture", "ui.gesture") {
                            val observation = results.getValue("r8-unknown-device").opt("content")
                            val frame = checkNotNull(findField(observation, "frame"))
                            check(ReleaseFixtureState.x > 0 && ReleaseFixtureState.y > 0)
                            val point = JSONObject().put("x", ReleaseFixtureState.x)
                            point.put("y", ReleaseFixtureState.y)
                            val stroke = JSONObject().put("points", JSONArray().put(point))
                            stroke.put("durationMillis", 15_000)
                            JSONObject().put("frame", frame).put("strokes", JSONArray().put(stroke))
                        },
                    )
                }

                "stale-capture" -> {
                    listOf(Step("r8-stale-snapshot", "ui.snapshot") { JSONObject() })
                }

                "stale-replay" -> {
                    listOf(
                        Step("r8-stale-old", "ui.click") { JSONObject().put("token", checkNotNull(oldToken)) },
                        Step("r8-stale-fresh-snapshot", "ui.snapshot") { JSONObject() },
                        Step("r8-stale-fresh", "ui.click") {
                            val observation = results.getValue("r8-stale-fresh-snapshot").opt("content")
                            val fresh = checkNotNull(findNode(observation)).getString("token")
                            check(fresh != oldToken)
                            JSONObject().put("token", fresh)
                        },
                    )
                }

                else -> {
                    error("Unsupported boundary $phase")
                }
            }
    }

    private fun chunk(
        delta: JSONObject,
        finish: Any,
    ) = JSONObject()
        .put("id", "fixture")
        .put("object", "chat.completion.chunk")
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish),
            ),
        )

    private fun call(
        id: String,
        tool: String,
        args: JSONObject,
    ): Pair<JSONObject, String> {
        val function = JSONObject().put("name", tool).put("arguments", args.toString())
        val call = JSONObject().put("id", id).put("index", 0)
        call.put("type", "function").put("function", function)
        val message = JSONObject().put("role", "assistant")
        message.put("tool_calls", JSONArray().put(call))
        return message to "tool_calls"
    }

    private fun text(value: String): Pair<JSONObject, String> {
        val message = JSONObject().put("role", "assistant").put("content", value)
        return message to "stop"
    }

    private fun respond(
        socket: Socket,
        type: String,
        body: String,
    ) {
        val bytes = body.toByteArray()
        val header =
            "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray())
            write(bytes)
            flush()
        }
    }

    override fun close() {
        server.close()
        workers.shutdownNow()
    }
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

private fun requireStableWindow(result: JSONObject) {
    val state = findField(result.opt("content"), "waitStatus")
    check(state == "STABLE" && findNode(result.opt("content")) != null) {
        "Fixture window did not become stable and observable: $state"
    }
}

private fun decoded(value: Any?): Any? =
    if (value is String && value.contains('{')) {
        runCatching { JSONObject(value.substring(value.indexOf('{'))) }.getOrNull()
    } else {
        value
    }

private fun findNode(value: Any?): JSONObject? {
    val item = decoded(value)
    if (item is JSONObject && item.optString("text") == "R8 target button") {
        if (item.has("token")) return item
    }
    return children(item).firstNotNullOfOrNull(::findNode)
}

private fun findField(
    value: Any?,
    key: String,
): String? {
    val item = decoded(value)
    if (item is JSONObject && item.has(key)) return item.getString(key)
    return children(item).firstNotNullOfOrNull { findField(it, key) }
}

private fun children(item: Any?): List<Any?> =
    when (item) {
        is JSONObject -> {
            item
                .keys()
                .asSequence()
                .map { item.opt(it) }
                .toList()
        }

        is JSONArray -> {
            (0 until item.length()).map { item.opt(it) }
        }

        else -> {
            emptyList()
        }
    }

package com.helix.app.eval

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** No socket or delegate exists. Synthetic responses test transport/tool plumbing, not autonomy. */
internal class SafOfflineModelWire(
    private val directory: File,
    private val root: String,
    private val relative: String,
) : WireClient {
    var calls = 0
    val statuses = mutableListOf<Int>()

    override suspend fun open(request: WireRequest): WireResponse {
        if (request.method == "GET") {
            check(request.url == "http://127.0.0.1:1/v1/models")
            val data =
                """{"object":"list","data":[{"id":"saf-offline","object":"model","context_length":128000}]}"""
                    .toByteArray()
            return response(data)
        }
        check(request.method == "POST" && request.url == "http://127.0.0.1:1/v1/chat/completions")
        check(calls < 5)
        val body = JSONObject(requireNotNull(request.body).toString(Charsets.UTF_8))
        return if (body.getJSONArray("messages").length() == 1 && body.toString().contains("Reply only OK.")) {
            val data =
                (
                    "data: {\"id\":\"probe\"," +
                        "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}," +
                        "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                ).toByteArray()
            response(data)
        } else {
            generation(body)
        }
    }

    private fun generation(body: JSONObject): WireResponse {
        File(
            directory,
            "request-$calls.json",
        ).writeText(
            JSONObject()
                .put("sent", body)
                .put("exactProductionBytes", true)
                .put("scriptedTransport", true)
                .toString(2),
        )
        val delta =
            when (calls++) {
                0 -> {
                    tool("glob", JSONObject().put("path", root).put("pattern", "**/*.md"))
                }

                1 -> {
                    tool("grep", JSONObject().put("path", root).put("query", "State: CURRENT"))
                }

                2 -> {
                    tool("read", JSONObject().put("path", root.trimEnd('/') + "/" + relative))
                }

                else -> {
                    answer(body)
                }
            }
        val finish = if (delta.has("tool_calls")) "tool_calls" else "stop"
        val chunk =
            JSONObject()
                .put(
                    "id",
                    "offline-$calls",
                ).put(
                    "object",
                    "chat.completion.chunk",
                ).put(
                    "model",
                    "saf-offline",
                ).put(
                    "choices",
                    JSONArray().put(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", finish)),
                )
        val bytes = "data: $chunk\n\ndata: [DONE]\n\n".toByteArray()
        statuses += 200
        return response(bytes)
    }

    private fun answer(body: JSONObject): JSONObject {
        val messages = body.getJSONArray("messages")
        val delivered =
            (0 until messages.length())
                .map { messages.getJSONObject(it) }
                .filter {
                    it.optString("role") ==
                        "tool"
                }.joinToString("\n") { it.optString("content") }
        val token = requireNotNull(Regex("CLEARANCE_[0-9a-f]+").find(delivered)).value
        val checkpoint = relative.substringAfterLast("release-").removeSuffix(".md")
        return JSONObject().put(
            "content",
            "$relative\n$token\nrestore synthetic checkpoint SEED-$checkpoint\n" +
                "The selected ORCHID record is CURRENT; archived records are SUPERSEDED.",
        )
    }

    private fun response(bytes: ByteArray) =
        WireResponse(
            200,
            mapOf("Content-Type" to listOf("text/event-stream")),
            object : WireBody {
                override suspend fun bytes() = bytes

                override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                    onChunk(bytes)
                }

                override fun close() = Unit
            },
        )

    private fun tool(
        name: String,
        args: JSONObject,
    ) = JSONObject().put(
        "tool_calls",
        JSONArray().put(
            JSONObject()
                .put(
                    "index",
                    0,
                ).put(
                    "id",
                    "saf-call-$calls",
                ).put(
                    "type",
                    "function",
                ).put("function", JSONObject().put("name", name).put("arguments", args.toString())),
        ),
    )
}

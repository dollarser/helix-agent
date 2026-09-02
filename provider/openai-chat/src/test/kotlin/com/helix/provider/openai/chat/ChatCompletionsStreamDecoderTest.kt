package com.helix.provider.openai.chat

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ToolCallId
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCompletionsStreamDecoderTest {
    // Fixture payloads are built by concatenation so the JSON escaping stays
    // auditable (raw strings cannot express the required backslash escapes).
    private fun chunk(json: String): String = "data: $json\n\n"

    private companion object {
        const val DONE_SSE = "data: [DONE]\n\n"
    }

    private fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun roleChunk(): String =
        chunk(
            "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\"," +
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}," +
                "\"finish_reason\":null}]}",
        )

    private fun contentChunk(delta: String): String =
        chunk(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"content\":${jsonStr(delta)}},\"finish_reason\":null}]}",
        )

    private fun finishChunk(reason: String): String =
        chunk(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{}," +
                "\"finish_reason\":$reason}]}",
        )

    private fun usageChunk(
        input: Int,
        output: Int,
    ): String =
        chunk(
            "{\"id\":\"chatcmpl-1\",\"choices\":[]," +
                "\"usage\":{\"prompt_tokens\":$input,\"completion_tokens\":$output," +
                "\"total_tokens\":${input + output}}}",
        )

    private fun toolStartChunk(
        index: Int,
        id: String,
        name: String,
    ): String =
        chunk(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                "{\"index\":$index,\"id\":\"$id\",\"type\":\"function\"," +
                "\"function\":{\"name\":\"$name\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}",
        )

    private fun toolArgsChunk(
        index: Int,
        frag: String,
    ): String =
        chunk(
            "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                "{\"index\":$index,\"function\":{\"arguments\":${jsonStr(frag)}}}]}" +
                ",\"finish_reason\":null}]}",
        )

    /**
     * The exact three-chunk stream Ollama's OpenAI-compatible /v1 emits for a tool
     * call (captured from ollama 0.33.0 + qwen2.5:3b-instruct, HXA-027 device
     * smoke): the COMPLETE arguments ride in the start fragment.
     */
    @Test
    fun ollamaArgumentsInStartFragmentAreEmitted() {
        val stream =
            chunk(
                "{\"id\":\"chatcmpl-812\",\"object\":\"chat.completion.chunk\"," +
                    "\"created\":1788178221,\"model\":\"qwen2.5:3b-instruct\"," +
                    "\"system_fingerprint\":\"fp_ollama\",\"choices\":[{\"index\":0," +
                    "\"delta\":{\"role\":\"assistant\",\"content\":\"\"," +
                    "\"tool_calls\":[{\"id\":\"call_tbxphj8z\",\"index\":0," +
                    "\"type\":\"function\"," +
                    "\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"probe\\\"}\"}}]}," +
                    "\"finish_reason\":null}]}",
            ) +
                chunk(
                    "{\"id\":\"chatcmpl-812\",\"object\":\"chat.completion.chunk\"," +
                        "\"created\":1788178221,\"model\":\"qwen2.5:3b-instruct\"," +
                        "\"system_fingerprint\":\"fp_ollama\",\"choices\":[{\"index\":0," +
                        "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                ) +
                DONE_SSE
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("call_tbxphj8z"), "echo"),
                ModelEvent.ToolArgumentsDelta(0, """{"text":"probe"}"""),
                ModelEvent.ToolCallFinished(0),
                ModelEvent.Completed("tool_calls"),
            ),
            decodeAll(stream),
        )
    }

    private fun decodeAll(text: String): List<ModelEvent> {
        val decoder = ChatCompletionsStreamDecoder()
        return decoder.feed(text.toByteArray()) + decoder.finish()
    }

    private fun decodeChunked(
        text: String,
        chunkSize: Int,
    ): List<ModelEvent> {
        val decoder = ChatCompletionsStreamDecoder()
        val bytes = text.toByteArray()
        val out = ArrayList<ModelEvent>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + chunkSize, bytes.size)
            out += decoder.feed(bytes.copyOfRange(i, end))
            i = end
        }
        out += decoder.finish()
        return out
    }

    @Test
    fun happyPathTextStream() {
        val stream =
            roleChunk() +
                contentChunk("Hel") +
                contentChunk("lo") +
                finishChunk("\"stop\"") +
                usageChunk(11, 3) +
                DONE_SSE
        // The usage chunk arrives after the finish chunk (vendor-documented
        // order with stream_options.include_usage), so Completed precedes Usage.
        assertEquals(
            listOf(
                ModelEvent.TextDelta("Hel"),
                ModelEvent.TextDelta("lo"),
                ModelEvent.Completed("stop"),
                ModelEvent.Usage(11, 3),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun arbitraryByteChunkingKeepsEvents() {
        val stream =
            contentChunk("a") +
                contentChunk("b") +
                finishChunk("\"stop\"") +
                usageChunk(1, 2) +
                DONE_SSE
        assertEquals(decodeAll(stream), decodeChunked(stream, 1))
        assertEquals(decodeAll(stream), decodeChunked(stream, 7))
        assertEquals(decodeAll(stream), decodeChunked(stream, 64))
    }

    @Test
    fun utf8MultibyteSplitAcrossChunks() {
        val stream = contentChunk("x😀y") + finishChunk("\"stop\"") + DONE_SSE
        val expected = decodeAll(stream)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("x😀y"), ModelEvent.Completed("stop")),
            expected,
        )
        // Cut exactly in the middle of the 4-byte emoji.
        val bytes = stream.toByteArray()
        val cut = bytes.indexOf(0xF0.toByte())
        require(cut >= 0) { "fixture must contain the emoji lead byte" }
        val decoder = ChatCompletionsStreamDecoder()
        val out =
            ArrayList<ModelEvent>().apply {
                addAll(decoder.feed(bytes.copyOfRange(0, cut + 2)))
                addAll(decoder.feed(bytes.copyOfRange(cut + 2, bytes.size)))
                addAll(decoder.finish())
            }
        assertEquals(expected, out)
    }

    @Test
    fun crlfLineEndings() {
        val stream = (contentChunk("hi") + finishChunk("\"stop\"")).replace("\n", "\r\n")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("hi"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun roleChunkAndEmptyDeltasAreDropped() {
        val emptyDelta =
            chunk(
                "{\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"}," +
                    "\"finish_reason\":null}]}",
            )
        val stream = roleChunk() + emptyDelta + contentChunk("real") + finishChunk("\"stop\"")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("real"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun commentPingsAreIgnored() {
        val stream =
            ": OPENAI:HTTP_REFUSAL_POLICY_NOTICE\n: ping\n" +
                contentChunk("hi") +
                finishChunk("\"stop\"")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("hi"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun multipleToolCallsInterleaved() {
        // Argument fragments (what the decoder must deliver):
        //   call_a: {"path"          + : "x"}        = {"path": "x"}
        //   call_b: {"path": "a"     + "}            = {"path": "a"}
        val frag1Open = "{" + "\"path\""
        val frag2Open = "{" + "\"path\"" + ": \"a"
        val frag1Close = ": " + "\"x\"" + "}"
        val frag2Close = "\"}"
        val stream =
            toolStartChunk(0, "call_a", "read") +
                toolStartChunk(1, "call_b", "write") +
                toolArgsChunk(0, frag1Open) +
                toolArgsChunk(1, frag2Open) +
                toolArgsChunk(0, frag1Close) +
                toolArgsChunk(1, frag2Close) +
                finishChunk("\"tool_calls\"") +
                usageChunk(5, 9) +
                DONE_SSE
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("call_a"), "read"),
                ModelEvent.ToolCallStarted(1, ToolCallId("call_b"), "write"),
                ModelEvent.ToolArgumentsDelta(0, frag1Open),
                ModelEvent.ToolArgumentsDelta(1, frag2Open),
                ModelEvent.ToolArgumentsDelta(0, frag1Close),
                ModelEvent.ToolArgumentsDelta(1, frag2Close),
                ModelEvent.ToolCallFinished(0),
                ModelEvent.ToolCallFinished(1),
                ModelEvent.Completed("tool_calls"),
                ModelEvent.Usage(5, 9),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun refusalViaContentFilter() {
        val stream = contentChunk("blocked") + finishChunk("\"content_filter\"") + DONE_SSE
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("blocked"), ModelEvent.Refusal("content_filter")),
            decodeAll(stream),
        )
    }

    @Test
    fun finishLengthCompletesWithLength() {
        val stream = contentChunk("trun") + finishChunk("\"length\"") + DONE_SSE
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("trun"), ModelEvent.Completed("length")),
            decodeAll(stream),
        )
    }

    @Test
    fun noTerminationFailsWithRetryableProtocolError() {
        // Stream ends (or the connection drops) before any finish chunk.
        val events = decodeAll(roleChunk() + contentChunk("Hel"))
        assertEquals(
            listOf(
                ModelEvent.TextDelta("Hel"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            events,
        )
    }

    @Test
    fun doneWithoutFinishChunkIsNoTermination() {
        // A bare [DONE] is a stream terminator, not a terminal event.
        val events = decodeAll(contentChunk("x") + DONE_SSE)
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            events,
        )
    }

    @Test
    fun droppedConnectionMidJsonFails() {
        // The final event's data line is cut in the middle and never terminated:
        // the partial line is discarded per the SSE spec, the stream never reaches
        // its finish chunk → retryable protocol failure (connection-level).
        val stream =
            contentChunk("x") +
                "data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"y\"},\"fini"
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun malformedJsonFailsWithNonRetryableProtocolError() {
        val events = decodeAll(chunk("{\"id\":\"c\",\"choices\":[{\"index\":0,"))
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            events,
        )
    }

    @Test
    fun chunkAfterTerminalFails() {
        val stream =
            contentChunk("x") + finishChunk("\"stop\"") + contentChunk("late")
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Completed("stop"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun errorObjectInChunkFails() {
        val stream = chunk("{\"error\":{\"message\":\"internal\",\"type\":\"server_error\"}}")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun unknownFinishReasonFails() {
        val stream = contentChunk("x") + finishChunk("\"network\"")
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun stopWithOpenToolCallsFails() {
        val stream = toolStartChunk(0, "call_a", "read") + finishChunk("\"stop\"")
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("call_a"), "read"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun argumentsForUnknownIndexFails() {
        val stream = toolArgsChunk(0, "{\"a\"}")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun duplicateToolCallStartFails() {
        val stream = toolStartChunk(0, "call_a", "read") + toolStartChunk(0, "call_b", "write")
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("call_a"), "read"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun explicitNullToolCallsFieldIsIgnored() {
        // sglang emits EXPLICIT null for `tool_calls` on text-only deltas (and omits the
        // field entirely on others): both mean "no tool fragments in this delta". The
        // decoder used to crash on the explicit null (JsonNull is not a JsonArray) and
        // kill the stream — a self-hosted sglang service was unusable.
        val stream =
            chunk(
                "{\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{" +
                    "\"role\":\"assistant\",\"content\":\"ok\",\"tool_calls\":null}," +
                    "\"finish_reason\":null}]}",
            ) + finishChunk("stop")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun explicitNullChoicesFieldIsIgnored() {
        // A deviating server may send an EXPLICIT null for `choices` (JsonNull is not a
        // Kotlin null, so `?.jsonArray` used to crash on it and kill the whole stream):
        // treat it like an absent/empty choices array, i.e. a usage-only chunk.
        val stream =
            chunk("{\"id\":\"c\",\"choices\":null}") +
                contentChunk("ok") +
                finishChunk("\"stop\"") +
                DONE_SSE
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun duplicateToolCallIdAcrossIndexesFails() {
        // The SAME id on a DIFFERENT index: downstream the app persists both rows
        // against the (turnId, callId) unique constraint and the strict history parser
        // rejects the duplicate at every later turn — a poisoned session. The stream
        // must fail NOW (non-retryable PROTOCOL), not every future request.
        val stream = toolStartChunk(0, "call_a", "read") + toolStartChunk(1, "call_a", "write")
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("call_a"), "read"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun parallelChoiceIndexIsRejected() {
        val stream =
            chunk(
                "{\"id\":\"c\",\"choices\":[{\"index\":1,\"delta\":{\"content\":\"x\"}," +
                    "\"finish_reason\":null}]}",
            )
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun vendorCallIdOutsideCharsetFails() {
        val stream = toolStartChunk(0, "call id with space", "read")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun decoderStopsProducingAfterFailure() {
        val bad = chunk("not json")
        val good = contentChunk("x") + finishChunk("\"stop\"")
        val decoder = ChatCompletionsStreamDecoder()
        val first = decoder.feed(bad.toByteArray())
        val second = decoder.feed(good.toByteArray())
        val third = decoder.finish()
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            first,
        )
        assertEquals(0, second.size)
        assertEquals(0, third.size)
    }

    @Test
    fun oversizeStreamLineFails() {
        val huge = "x".repeat(1_048_576)
        val stream = "data: {\"content\":\"$huge\"}\n\n"
        val decoder = ChatCompletionsStreamDecoder()
        val events = decoder.feed(stream.toByteArray())
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            events,
        )
    }
}

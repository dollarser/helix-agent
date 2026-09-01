package com.helix.provider.openai.responses

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ToolCallId
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsesStreamDecoderTest {
    private fun sse(
        type: String,
        data: String,
    ): String = "event: $type\ndata: $data\n\n"

    // Fixture payloads are built by concatenation so the JSON escaping stays
    // auditable (raw strings cannot express the required backslash escapes).
    private fun createdJson(seq: Int): String = "{\"type\":\"response.created\",\"sequence_number\":$seq}"

    private fun inProgressJson(seq: Int): String = "{\"type\":\"response.in_progress\",\"sequence_number\":$seq}"

    private fun messageItemAddedJson(seq: Int): String =
        "{\"type\":\"response.output_item.added\",\"sequence_number\":$seq,\"output_index\":0," +
            "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"in_progress\"," +
            "\"role\":\"assistant\",\"content\":[]}}"

    private fun contentPartAddedJson(seq: Int): String =
        "{\"type\":\"response.content_part.added\",\"sequence_number\":$seq," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0}"

    private fun textDeltaJson(
        seq: Int,
        delta: String,
    ): String =
        "{\"type\":\"response.output_text.delta\",\"sequence_number\":$seq," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":$delta}"

    private fun textDoneJson(seq: Int): String =
        "{\"type\":\"response.output_text.done\",\"sequence_number\":$seq," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"text\":\"final\"}"

    private fun messageItemDoneJson(
        seq: Int,
        status: String,
    ): String =
        "{\"type\":\"response.output_item.done\",\"sequence_number\":$seq,\"output_index\":0," +
            "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"$status\"}}"

    private fun completedJson(
        seq: Int,
        input: Int,
        output: Int,
    ): String =
        "{\"type\":\"response.completed\",\"sequence_number\":$seq," +
            "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\"," +
            "\"usage\":{\"input_tokens\":$input,\"output_tokens\":$output," +
            "\"total_tokens\":${input + output}}}}"

    private fun textStream(vararg deltas: String): String =
        buildString {
            append(sse("response.created", createdJson(0)))
            append(sse("response.in_progress", inProgressJson(1)))
            append(sse("response.output_item.added", messageItemAddedJson(2)))
            append(sse("response.content_part.added", contentPartAddedJson(3)))
            deltas.forEachIndexed { i, delta ->
                append(sse("response.output_text.delta", textDeltaJson(4 + i, jsonStr(delta))))
            }
            append(sse("response.output_text.done", textDoneJson(4 + deltas.size)))
            append(sse("response.output_item.done", messageItemDoneJson(5 + deltas.size, "completed")))
            append(sse("response.completed", completedJson(6 + deltas.size, 11, 3)))
        }

    private fun jsonStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A `response.output_item.added` carrying a function_call item. */
    private fun functionCallItemAdded(
        outputIndex: Int,
        itemId: String,
        callId: String,
        name: String,
    ): String =
        sse(
            "response.output_item.added",
            "{\"type\":\"response.output_item.added\",\"output_index\":$outputIndex," +
                "\"item\":{\"id\":\"$itemId\",\"type\":\"function_call\",\"status\":\"in_progress\"," +
                "\"call_id\":\"$callId\",\"name\":\"$name\",\"arguments\":\"\"}}",
        )

    private fun decodeAll(text: String): List<ModelEvent> {
        val decoder = ResponsesStreamDecoder()
        return decoder.feed(text.toByteArray()) + decoder.finish()
    }

    private fun decodeChunked(
        text: String,
        chunkSize: Int,
    ): List<ModelEvent> {
        val decoder = ResponsesStreamDecoder()
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
        val events = decodeAll(textStream("Hel", "lo"))
        assertEquals(
            listOf(
                ModelEvent.TextDelta("Hel"),
                ModelEvent.TextDelta("lo"),
                ModelEvent.Usage(11, 3),
                ModelEvent.Completed("stop"),
            ),
            events,
        )
    }

    @Test
    fun arbitraryByteChunkingKeepsEvents() {
        val stream = textStream("a", "b", "c")
        assertEquals(decodeAll(stream), decodeChunked(stream, 1))
        assertEquals(decodeAll(stream), decodeChunked(stream, 7))
        assertEquals(decodeAll(stream), decodeChunked(stream, 64))
    }

    @Test
    fun utf8MultibyteSplitAcrossChunks() {
        // 😀 (4 bytes) split 2+2 by a chunk boundary inside the payload.
        val stream = textStream("x😀y")
        val expected = decodeAll(stream)
        assertEquals(
            listOf<ModelEvent>(
                ModelEvent.TextDelta("x😀y"),
                ModelEvent.Usage(11, 3),
                ModelEvent.Completed("stop"),
            ),
            expected,
        )
        // Re-encode and cut exactly in the middle of the emoji.
        val bytes = stream.toByteArray()
        val cut = bytes.indexOf(0xF0.toByte())
        require(cut >= 0) { "fixture must contain the emoji lead byte" }
        val decoder = ResponsesStreamDecoder()
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
        val stream =
            sse("response.output_text.delta", """{"type":"response.output_text.delta","delta":"hi"}""")
                .replace("\n", "\r\n") +
                sse(
                    "response.completed",
                    """{"type":"response.completed","response":{"status":"completed"}}""",
                ).replace("\n", "\r\n")
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("hi"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun reasoningDeltasAreMapped() {
        val deltaJson =
            "{\"type\":\"response.reasoning_summary_text.delta\"," +
                "\"item_id\":\"rs_1\",\"output_index\":0,\"summary_index\":0,\"delta\":\"hmm…\"}"
        val stream =
            sse("response.reasoning_summary_text.delta", deltaJson) +
                sse(
                    "response.completed",
                    """{"type":"response.completed","response":{"status":"completed"}}""",
                )
        assertEquals(
            listOf<ModelEvent>(ModelEvent.ReasoningDelta("hmm…"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun multipleToolCallsInterleaved() {
        // Argument fragments (what the decoder must deliver):
        //   fc_1: {"path"          + : "x"}        = {"path": "x"}
        //   fc_2: {"path": "a"     + "}            = {"path": "a"}
        val frag1Open = "{" + "\"path\""
        val frag2Open = "{" + "\"path\"" + ": \"a"
        val frag1Close = ": " + "\"x\"" + "}"
        val frag2Close = "\"}"

        // Build the payloads explicitly to keep the JSON escaping auditable.
        fun argsDelta(
            seq: Int,
            itemId: String,
            index: Int,
            frag: String,
        ): String =
            sse(
                "response.function_call_arguments.delta",
                "{\"type\":\"response.function_call_arguments.delta\",\"sequence_number\":$seq," +
                    "\"item_id\":\"$itemId\",\"output_index\":$index,\"delta\":${jsonStr(frag)}}",
            )

        fun itemAddedFor(
            seq: Int,
            index: Int,
            itemId: String,
            callId: String,
            name: String,
        ): String =
            sse(
                "response.output_item.added",
                "{\"type\":\"response.output_item.added\",\"sequence_number\":$seq," +
                    "\"output_index\":$index," +
                    "\"item\":{\"id\":\"$itemId\",\"type\":\"function_call\",\"status\":\"in_progress\"," +
                    "\"call_id\":\"$callId\",\"name\":\"$name\",\"arguments\":\"\"}}",
            )

        fun argsDone(
            seq: Int,
            itemId: String,
            index: Int,
            name: String,
            args: String,
        ): String =
            sse(
                "response.function_call_arguments.done",
                "{\"type\":\"response.function_call_arguments.done\",\"sequence_number\":$seq," +
                    "\"item_id\":\"$itemId\",\"output_index\":$index," +
                    "\"name\":\"$name\",\"arguments\":${jsonStr(args)}}",
            )

        val stream =
            itemAddedFor(1, 1, "fc_1", "call_abc", "read") +
                itemAddedFor(2, 2, "fc_2", "call_def", "write") +
                argsDelta(3, "fc_1", 1, frag1Open) +
                argsDelta(4, "fc_2", 2, frag2Open) +
                argsDelta(5, "fc_1", 1, frag1Close) +
                argsDone(6, "fc_1", 1, "read", frag1Open + frag1Close) +
                argsDelta(7, "fc_2", 2, frag2Close) +
                argsDone(8, "fc_2", 2, "write", frag2Open + frag2Close) +
                sse("response.completed", completedJson(9, 5, 9))
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(1, ToolCallId("call_abc"), "read"),
                ModelEvent.ToolCallStarted(2, ToolCallId("call_def"), "write"),
                ModelEvent.ToolArgumentsDelta(1, frag1Open),
                ModelEvent.ToolArgumentsDelta(2, frag2Open),
                ModelEvent.ToolArgumentsDelta(1, frag1Close),
                ModelEvent.ToolCallFinished(1),
                ModelEvent.ToolArgumentsDelta(2, frag2Close),
                ModelEvent.ToolCallFinished(2),
                ModelEvent.Usage(5, 9),
                ModelEvent.Completed("tool_calls"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun refusalViaContentFilter() {
        val itemDoneJson =
            "{\"type\":\"response.output_item.done\",\"sequence_number\":2,\"output_index\":0," +
                "\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"incomplete\"," +
                "\"incomplete_details\":{\"reason\":\"content_filter\"}}}"
        val incompleteJson =
            "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"," +
                "\"incomplete_details\":{\"reason\":\"content_filter\"}," +
                "\"usage\":{\"input_tokens\":4,\"output_tokens\":0}}}"
        val stream =
            sse("response.output_item.done", itemDoneJson) +
                sse("response.incomplete", incompleteJson)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Usage(4, 0), ModelEvent.Refusal("content_filter")),
            decodeAll(stream),
        )
    }

    @Test
    fun incompleteMaxTokensCompletesWithLength() {
        val incompleteJson =
            "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"," +
                "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}," +
                "\"usage\":{\"input_tokens\":2,\"output_tokens\":128}}}"
        val stream = sse("response.incomplete", incompleteJson)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Usage(2, 128), ModelEvent.Completed("length")),
            decodeAll(stream),
        )
    }

    @Test
    fun argumentsDeltaForUnknownIndexFails() {
        // The event contract: deltas reference a tool call BY STARTED index. An orphan
        // delta was silently accepted (and dropped by strict consumers); now it fails
        // the stream like the sibling decoders do.
        val stream =
            sse(
                "response.function_call_arguments.delta",
                "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_x\"," +
                    "\"output_index\":3,\"delta\":\"{\\\\\"a\\\\\"}\"}",
            ) +
                sse(
                    "response.completed",
                    """{"type":"response.completed","response":{"status":"completed"}}""",
                )
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun argumentsDoneForUnknownIndexFails() {
        val stream =
            sse(
                "response.function_call_arguments.done",
                "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_x\"," +
                    "\"output_index\":7,\"name\":\"read\",\"arguments\":\"{}\"}",
            )
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun duplicateItemAddedForSameIndexFails() {
        val added = functionCallItemAdded(1, "fc_1", "call_1", "read")
        val stream = added + added
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(1, ToolCallId("call_1"), "read"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun duplicateCallIdAcrossIndexesFails() {
        // The SAME call id on a DIFFERENT output index: the app would persist both rows
        // against the (turnId, callId) unique constraint and the strict history parser
        // would reject the duplicate at every later turn — a poisoned session. Fail now.
        val added1 = functionCallItemAdded(1, "fc_1", "call_1", "read")
        val added2 = functionCallItemAdded(2, "fc_2", "call_1", "write")
        val stream = added1 + added2
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(1, ToolCallId("call_1"), "read"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun completedWithOpenFunctionCallFails() {
        // RISK-1 alignment: a response that terminates while a function call is still
        // open (truncated mid-arguments) is a protocol failure — the chat/anthropic
        // decoders fail it, and this one used to report a clean length-completion that
        // let the app SILENTLY DROP the truncated call (never persisted, never
        // back-filled, turn marked COMPLETED).
        val added = functionCallItemAdded(1, "fc_1", "call_1", "read")
        val delta =
            sse(
                "response.function_call_arguments.delta",
                "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\"," +
                    "\"output_index\":1,\"delta\":\"{\\\"path\\\"}\"}",
            )
        val incomplete =
            sse(
                "response.incomplete",
                """{"type":"response.incomplete","response":{"status":"incomplete",""" +
                    """"incomplete_details":{"reason":"max_output_tokens"},""" +
                    """"usage":{"input_tokens":2,"output_tokens":128}}}""",
            )
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(1, ToolCallId("call_1"), "read"),
                ModelEvent.ToolArgumentsDelta(1, """{"path"}"""),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(added + delta + incomplete),
        )
    }

    @Test
    fun usageAbsentMeansNoUsageEvent() {
        val stream =
            sse(
                "response.completed",
                """{"type":"response.completed","response":{"status":"completed"}}""",
            )
        assertEquals(listOf<ModelEvent>(ModelEvent.Completed("stop")), decodeAll(stream))
    }

    @Test
    fun noTerminationFailsWithRetryableProtocolError() {
        val stream = textStream("Hel") // ends mid-stream: no completed/failed event
        val streamWithoutTerminal =
            stream.substringBefore("event: response.output_item.done")
        val events = decodeAll(streamWithoutTerminal)
        assertEquals(
            listOf(
                ModelEvent.TextDelta("Hel"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            events,
        )
    }

    @Test
    fun droppedConnectionMidJsonFails() {
        // The final event's data line is cut in the middle and never terminated:
        // the partial line is discarded per the SSE spec, the stream simply never
        // reaches its terminal event → retryable protocol failure (the connection
        // likely dropped; a retry may succeed).
        val prefix = textStream("x").substringBefore("event: response.completed")
        val truncated =
            prefix +
                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"statu"
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            decodeAll(truncated),
        )
    }

    @Test
    fun midStreamErrorEventIsMapped() {
        val rateLimitJson =
            "{\"type\":\"error\",\"sequence_number\":3,\"code\":\"rate_limit_exceeded\"," +
                "\"message\":\"slow down\"}"
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.RATE_LIMITED, retryable = true)),
            decodeAll(sse("error", rateLimitJson)),
        )
        val serverJson =
            "{\"type\":\"error\",\"sequence_number\":3,\"code\":\"server_error\",\"message\":\"boom\"}"
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, retryable = true)),
            decodeAll(sse("error", serverJson)),
        )
        val unknownJson =
            "{\"type\":\"error\",\"sequence_number\":3,\"code\":\"mystery_code\",\"message\":\"??\"}"
        // Unknown codes fail closed and are not retried.
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, retryable = false)),
            decodeAll(sse("error", unknownJson)),
        )
    }

    @Test
    fun responseFailedIsMapped() {
        val failedJson =
            "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\"," +
                "\"error\":{\"code\":\"invalid_request_error\",\"message\":\"bad\"}}"
        val stream = sse("response.failed", failedJson)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun malformedJsonInHandledEventFails() {
        val stream = sse("response.output_text.delta", """{"type":"response.output_text.delta","delta":"x""")
        val events = decodeAll(stream)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            events,
        )
    }

    @Test
    fun missingRequiredFieldFails() {
        val stream =
            sse(
                "response.function_call_arguments.delta",
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1"}""",
            )
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun vendorCallIdOutsideCharsetFails() {
        val addedJson =
            "{\"type\":\"response.output_item.added\",\"output_index\":1," +
                "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\"," +
                "\"call_id\":\"call id with space\",\"name\":\"read\"}}"
        val stream = sse("response.output_item.added", addedJson)
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun eventAfterTerminalFails() {
        val stream =
            sse(
                "response.completed",
                """{"type":"response.completed","response":{"status":"completed"}}""",
            ) +
                sse(
                    "response.output_text.delta",
                    """{"type":"response.output_text.delta","delta":"late"}""",
                )
        assertEquals(
            listOf(
                ModelEvent.Completed("stop"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun unknownAndNoiseEventsAreIgnored() {
        val stream =
            sse("ping", "{}") +
                sse("response.audio.delta", """{"type":"response.audio.delta","delta":"..."}""") +
                textStream("ok")
        val events = decodeAll(stream)
        assertEquals(
            listOf(
                ModelEvent.TextDelta("ok"),
                ModelEvent.Usage(11, 3),
                ModelEvent.Completed("stop"),
            ),
            events,
        )
    }

    @Test
    fun emptyDeltasAreDroppedNotEmitted() {
        fun deltaJson(value: String): String =
            "{\"type\":\"response.output_text.delta\",\"item_id\":\"m\",\"output_index\":0," +
                "\"content_index\":0,\"delta\":\"$value\"}"
        val stream =
            sse("response.output_text.delta", deltaJson("")) +
                sse("response.output_text.delta", deltaJson("real")) +
                sse(
                    "response.completed",
                    """{"type":"response.completed","response":{"status":"completed"}}""",
                )
        assertEquals(
            listOf<ModelEvent>(ModelEvent.TextDelta("real"), ModelEvent.Completed("stop")),
            decodeAll(stream),
        )
    }

    @Test
    fun oversizeStreamLineFails() {
        val huge = "x".repeat(1_048_576)
        val stream = sse("response.output_text.delta", """{"type":"response.output_text.delta","delta":"$huge"}""")
        val decoder = ResponsesStreamDecoder()
        val events = decoder.feed(stream.toByteArray())
        assertEquals(
            listOf<ModelEvent>(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            events,
        )
    }

    @Test
    fun decoderStopsProducingAfterFailure() {
        val bad = sse("response.output_text.delta", "not json")
        val good = sse("response.completed", """{"type":"response.completed","response":{"status":"completed"}}""")
        val decoder = ResponsesStreamDecoder()
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
}

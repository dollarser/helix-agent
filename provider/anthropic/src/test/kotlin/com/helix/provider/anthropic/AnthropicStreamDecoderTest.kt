package com.helix.provider.anthropic

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ToolCallId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream decoder tests (HXA-024): happy paths, arbitrary byte chunking,
 * UTF-8 splits, block ordering constraints, stop reasons, error mapping,
 * no-termination and post-terminal guards.
 */
class AnthropicStreamDecoderTest {
    private fun jsonStr(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun sse(
        type: String,
        json: String,
    ): String = "event: $type\ndata: $json\n\n"

    private fun messageStart(input: Int): String =
        sse(
            "message_start",
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\"," +
                "\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":[]," +
                "\"stop_reason\":null,\"usage\":{\"input_tokens\":$input,\"output_tokens\":1}}}",
        )

    private fun messageStartNoUsage(): String =
        sse(
            "message_start",
            "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\"," +
                "\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":[]," +
                "\"stop_reason\":null,\"usage\":{}}}",
        )

    private fun textStart(index: Int): String =
        sse(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":$index,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
        )

    private fun thinkingStart(index: Int): String =
        sse(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":$index," +
                "\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
        )

    private fun toolStart(
        index: Int,
        id: String,
        name: String,
    ): String =
        sse(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":$index," +
                "\"content_block\":{\"type\":\"tool_use\",\"id\":${jsonStr(id)}," +
                "\"name\":${jsonStr(name)},\"input\":{}}}",
        )

    private fun futureBlockStart(index: Int): String =
        sse(
            "content_block_start",
            "{\"type\":\"content_block_start\",\"index\":$index," +
                "\"content_block\":{\"type\":\"future_block\",\"data\":\"opaque\"}}",
        )

    private fun textDelta(
        index: Int,
        text: String,
    ): String =
        sse(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":$index," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":${jsonStr(text)}}}",
        )

    private fun thinkingDelta(
        index: Int,
        text: String,
    ): String =
        sse(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":$index," +
                "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":${jsonStr(text)}}}",
        )

    private fun inputJsonDelta(
        index: Int,
        fragment: String,
    ): String =
        sse(
            "content_block_delta",
            "{\"type\":\"content_block_delta\",\"index\":$index," +
                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":${jsonStr(fragment)}}}",
        )

    private fun blockStop(index: Int): String =
        sse("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":$index}")

    private fun messageDelta(
        stopReason: String,
        output: Int,
    ): String =
        sse(
            "message_delta",
            "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":${jsonStr(stopReason)},\"stop_sequence\":null}," +
                "\"usage\":{\"output_tokens\":$output}}",
        )

    private fun messageStop(): String = sse("message_stop", "{\"type\":\"message_stop\"}")

    private fun errorEvent(type: String): String =
        sse(
            "error",
            "{\"type\":\"error\",\"error\":{\"type\":${jsonStr(type)},\"message\":\"vendor\"}}",
        )

    private fun decodeAll(stream: String): List<ModelEvent> {
        val decoder = AnthropicStreamDecoder()
        val out = ArrayList<ModelEvent>()
        out += decoder.feed(stream.toByteArray())
        out += decoder.finish()
        return out
    }

    @Test
    fun happyPathText() {
        val stream =
            messageStart(25) +
                textStart(0) +
                textDelta(0, "Hel") +
                textDelta(0, "lo") +
                blockStop(0) +
                messageDelta("end_turn", 35) +
                messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("Hel"),
                ModelEvent.TextDelta("lo"),
                ModelEvent.Usage(25, 35),
                ModelEvent.Completed("stop"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun arbitraryByteChunkingKeepsEvents() {
        val stream =
            messageStart(1) +
                textStart(0) +
                textDelta(0, "a") +
                blockStop(0) +
                messageDelta("end_turn", 2) +
                messageStop()
        val whole = decodeAll(stream)
        for (size in listOf(1, 7, 64)) {
            val decoder = AnthropicStreamDecoder()
            val bytes = stream.toByteArray()
            val out = ArrayList<ModelEvent>()
            var i = 0
            while (i < bytes.size) {
                out += decoder.feed(bytes.copyOfRange(i, minOf(i + size, bytes.size)))
                i += size
            }
            out += decoder.finish()
            assertEquals("chunk size $size", whole, out)
        }
        assertEquals(
            listOf(
                ModelEvent.TextDelta("a"),
                ModelEvent.Usage(1, 2),
                ModelEvent.Completed("stop"),
            ),
            whole,
        )
    }

    @Test
    fun utf8SplitAcrossChunks() {
        // Split the 4-byte emoji in the middle: 1 byte, then 2, then the rest.
        val stream = messageStart(1) + textStart(0) + textDelta(0, "x😀y") + blockStop(0)
        val bytes = stream.toByteArray()
        val emoji = "😀".toByteArray()
        val start = indexOf(bytes, emoji)
        assertTrue(start >= 0)
        val decoder = AnthropicStreamDecoder()
        val out =
            decoder.feed(bytes.copyOfRange(0, start + 1)) +
                decoder.feed(bytes.copyOfRange(start + 1, start + 3)) +
                decoder.feed(bytes.copyOfRange(start + 3, bytes.size))
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x😀y"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            out + decoder.finish(),
        )
    }

    private fun indexOf(
        haystack: ByteArray,
        needle: ByteArray,
    ): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @Test
    fun crlfLineBreaks() {
        val stream =
            "event: content_block_start\r\ndata: " +
                "{\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\r\n\r\n" +
                "event: content_block_delta\r\ndata: " +
                "{\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\r\n\r\n"
        val decoder = AnthropicStreamDecoder()
        val out = decoder.feed(stream.toByteArray()) + decoder.finish()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("ok"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            out,
        )
    }

    @Test
    fun pingIgnored() {
        val stream =
            sse("ping", "{\"type\":\"ping\"}") +
                messageStart(2) +
                textStart(0) +
                textDelta(0, "hi") +
                sse("ping", "{\"type\":\"ping\"}") +
                blockStop(0) +
                messageDelta("end_turn", 3) +
                messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("hi"),
                ModelEvent.Usage(2, 3),
                ModelEvent.Completed("stop"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun unknownEventIgnored() {
        val stream =
            messageStart(2) +
                sse("future_event", "{\"type\":\"future_event\"}") +
                textStart(0) +
                textDelta(0, "hi") +
                blockStop(0) +
                messageDelta("end_turn", 3) +
                messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("hi"),
                ModelEvent.Usage(2, 3),
                ModelEvent.Completed("stop"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun thinkingBlockEmitsReasoningDeltas() {
        val stream =
            messageStart(4) +
                thinkingStart(0) +
                thinkingDelta(0, "hmm") +
                thinkingDelta(0, "...") +
                blockStop(0) +
                textStart(1) +
                textDelta(1, "out") +
                blockStop(1) +
                messageDelta("end_turn", 7) +
                messageStop()
        assertEquals(
            listOf(
                ModelEvent.ReasoningDelta("hmm"),
                ModelEvent.ReasoningDelta("..."),
                ModelEvent.TextDelta("out"),
                ModelEvent.Usage(4, 7),
                ModelEvent.Completed("stop"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun toolCallStream() {
        val stream =
            messageStart(5) +
                toolStart(0, "toolu_1", "read") +
                inputJsonDelta(0, "{\"path\":") +
                inputJsonDelta(0, "\"/a\"}") +
                blockStop(0) +
                messageDelta("tool_use", 9) +
                messageStop()
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("toolu_1"), "read"),
                ModelEvent.ToolArgumentsDelta(0, "{\"path\":"),
                ModelEvent.ToolArgumentsDelta(0, "\"/a\"}"),
                ModelEvent.ToolCallFinished(0),
                ModelEvent.Usage(5, 9),
                ModelEvent.Completed("tool_calls"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun multipleBlocksInterleaved() {
        val stream =
            messageStart(3) +
                textStart(0) +
                textDelta(0, "A") +
                toolStart(1, "toolu_1", "read") +
                inputJsonDelta(1, "{") +
                textDelta(0, "B") +
                blockStop(1) +
                blockStop(0) +
                messageDelta("tool_use", 4) +
                messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("A"),
                ModelEvent.ToolCallStarted(1, ToolCallId("toolu_1"), "read"),
                ModelEvent.ToolArgumentsDelta(1, "{"),
                ModelEvent.TextDelta("B"),
                ModelEvent.ToolCallFinished(1),
                ModelEvent.Usage(3, 4),
                ModelEvent.Completed("tool_calls"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun unknownBlockKindIgnored() {
        val stream =
            messageStart(1) +
                futureBlockStart(0) +
                inputJsonDelta(0, "x") +
                blockStop(0) +
                textStart(1) +
                textDelta(1, "ok") +
                blockStop(1) +
                messageDelta("end_turn", 2) +
                messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("ok"),
                ModelEvent.Usage(1, 2),
                ModelEvent.Completed("stop"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun stopSequenceReasonMapsToStop() {
        val stream =
            messageStart(1) + textStart(0) + textDelta(0, "x") + blockStop(0) +
                messageDelta("stop_sequence", 2) + messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Usage(1, 2),
                ModelEvent.Completed("stop"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun maxTokensReasonMapsToLength() {
        val stream =
            messageStart(1) + textStart(0) + textDelta(0, "trun") + blockStop(0) +
                messageDelta("max_tokens", 2) + messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("trun"),
                ModelEvent.Usage(1, 2),
                ModelEvent.Completed("length"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun refusalReasonIsLegitimateRefusal() {
        val stream =
            messageStart(1) + textStart(0) + textDelta(0, "no") + blockStop(0) +
                messageDelta("refusal", 2) + messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("no"),
                ModelEvent.Usage(1, 2),
                ModelEvent.Refusal("refusal"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun usageNullWhenVendorOmitsFigures() {
        val stream =
            messageStartNoUsage() + textStart(0) + textDelta(0, "x") + blockStop(0) +
                messageDelta("end_turn", 3) + messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Usage(null, 3),
                ModelEvent.Completed("stop"),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun noTerminationIsRetryable() {
        val stream = messageStart(1) + textStart(0) + textDelta(0, "x")
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun messageStopWithoutDeltaIsNoTermination() {
        val stream = messageStart(1) + messageStop()
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true)),
            decodeAll(stream),
        )
    }

    @Test
    fun droppedConnectionMidJsonIsRetryable() {
        // The partial line is discarded per the SSE spec: no termination.
        val decoder = AnthropicStreamDecoder()
        val stream =
            messageStart(1) + textStart(0) +
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
        val out = decoder.feed(stream.toByteArray()) + decoder.finish()
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = true)),
            out,
        )
    }

    @Test
    fun malformedJsonFailsStream() {
        val stream = messageStart(1) + sse("content_block_delta", "{not json") + textDelta(0, "y")
        val events = decodeAll(stream)
        assertEquals(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false), events.last())
        assertTrue(events.size >= 1)
    }

    @Test
    fun errorEventOverloaded() {
        val stream = messageStart(1) + errorEvent("overloaded_error")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, retryable = true)),
            decodeAll(stream),
        )
    }

    @Test
    fun errorEventRateLimit() {
        val stream = messageStart(1) + errorEvent("rate_limit_error")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.RATE_LIMITED, retryable = true)),
            decodeAll(stream),
        )
    }

    @Test
    fun errorEventInvalidRequest() {
        val stream = messageStart(1) + errorEvent("invalid_request_error")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun errorEventUnknownTypeFailsClosed() {
        val stream = messageStart(1) + errorEvent("mystery_error")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.SERVER_ERROR, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun eventAfterTerminalFailsStream() {
        val stream =
            messageStart(1) + textStart(0) + textDelta(0, "x") + blockStop(0) +
                messageDelta("end_turn", 2) + messageStop() + textDelta(0, "late")
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Usage(1, 2),
                ModelEvent.Completed("stop"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun eventAfterMessageStopBeforeTerminalFailsStream() {
        val stream =
            messageStart(1) + messageStop() + textStart(0) + textDelta(0, "x")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun deltaForUnknownIndexFailsStream() {
        val stream = messageStart(1) + textDelta(0, "x")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun deltaAfterBlockStopFailsStream() {
        val stream = messageStart(1) + textStart(0) + blockStop(0) + textDelta(0, "x")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun deltaKindMismatchFailsStream() {
        // The block start was a valid event (emitted); the mismatched delta
        // fails the stream without leaking further events.
        val stream = messageStart(1) + toolStart(0, "toolu_1", "read") + textDelta(0, "x")
        assertEquals(
            listOf(
                ModelEvent.ToolCallStarted(0, ToolCallId("toolu_1"), "read"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun messageDeltaWithOpenBlockFailsStream() {
        val stream = messageStart(1) + textStart(0) + textDelta(0, "x") + messageDelta("end_turn", 2)
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun duplicateBlockStartFailsStream() {
        val stream = messageStart(1) + textStart(0) + textStart(0)
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun unknownStopReasonFailsStream() {
        val stream =
            messageStart(1) + textStart(0) + textDelta(0, "x") + blockStop(0) +
                messageDelta("network", 2) + messageStop()
        assertEquals(
            listOf(
                ModelEvent.TextDelta("x"),
                ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false),
            ),
            decodeAll(stream),
        )
    }

    @Test
    fun toolCallIdOutsideCharsetFailsStream() {
        val stream = messageStart(1) + toolStart(0, "tool u 1", "read")
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            decodeAll(stream),
        )
    }

    @Test
    fun decoderStopsProducingAfterFailure() {
        val decoder = AnthropicStreamDecoder()
        val bad = sse("content_block_delta", "{bad json")
        val good = textStart(0) + textDelta(0, "ok") + blockStop(0)
        val out = decoder.feed((bad + good).toByteArray()) + decoder.finish()
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            out,
        )
        assertFalse(decoder.failure.isNullOrBlank())
    }

    @Test
    fun oversizeLineFailsStream() {
        val decoder = AnthropicStreamDecoder()
        val line = "data: " + "x".repeat(1_048_577) + "\n\n"
        val out = decoder.feed(line.toByteArray()) + decoder.finish()
        assertEquals(
            listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, retryable = false)),
            out,
        )
    }
}

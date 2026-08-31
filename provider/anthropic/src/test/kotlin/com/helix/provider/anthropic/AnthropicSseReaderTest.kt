package com.helix.provider.anthropic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE reader tests (HXA-024): one line split across chunks, multi-line data,
 * UTF-8 byte boundaries, typed `event:` fields, stream tail handling.
 */
class AnthropicSseReaderTest {
    private fun events(vararg chunks: String): List<AnthropicSseEvent> {
        val reader = AnthropicSseReader()
        val out = reader.feed(chunks.joinToString("") { it }.toByteArray()) + reader.finish()
        return out
    }

    @Test
    fun simpleTypedEvent() {
        assertEquals(
            listOf(AnthropicSseEvent("message_stop", "{\"type\":\"message_stop\"}")),
            events("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
        )
    }

    @Test
    fun dataOnlyDefaultsToMessageType() {
        assertEquals(
            listOf(AnthropicSseEvent("message", "{\"type\":\"ping\"}")),
            events("data: {\"type\":\"ping\"}\n\n"),
        )
    }

    @Test
    fun multiLineDataJoinsWithLf() {
        assertEquals(
            listOf(AnthropicSseEvent("message", "line1\nline2")),
            events("data: line1\ndata: line2\n\n"),
        )
    }

    @Test
    fun crlfAndBareCrLineBreaks() {
        assertEquals(
            listOf(
                AnthropicSseEvent("message_stop", "a"),
                AnthropicSseEvent("message_stop", "b"),
            ),
            events("event: message_stop\r\ndata: a\r\n\r\nevent: message_stop\r\ndata: b\r\r"),
        )
    }

    @Test
    fun commentsAreIgnored() {
        assertEquals(
            listOf(AnthropicSseEvent("message_stop", "a")),
            events(": ping comment\nevent: message_stop\ndata: a\n\n: another\n\n"),
        )
    }

    @Test
    fun bareDataFieldDispatchesEmptyValue() {
        // An empty data value does not dispatch a payload (the reader skips it).
        assertEquals(emptyList<AnthropicSseEvent>(), events("data:\n\n"))
        // `data:value` strips no space.
        assertEquals(
            listOf(AnthropicSseEvent("message", "value")),
            events("data:value\n\n"),
        )
    }

    @Test
    fun multipleEventsInOneChunk() {
        assertEquals(
            listOf(
                AnthropicSseEvent("message_start", "1"),
                AnthropicSseEvent("message_stop", "2"),
            ),
            events("event: message_start\ndata: 1\n\nevent: message_stop\ndata: 2\n\n"),
        )
    }

    @Test
    fun oneByteAtATime() {
        val full = "event: message_stop\ndata: {\"t\":1}\n\n"
        val reader = AnthropicSseReader()
        val out =
            full
                .toByteArray()
                .map { b ->
                    val one = byteArrayOf(b)
                    reader.feed(one)
                }.flatten() + reader.finish()
        assertEquals(listOf(AnthropicSseEvent("message_stop", "{\"t\":1}")), out)
        assertFalse(reader.isFailed)
    }

    @Test
    fun multiByteUtf8SplitAcrossChunks() {
        // emoji = 4 bytes (F0 9F 98 80), split 2+2 inside the data payload.
        val reader = AnthropicSseReader()
        val head = "data: x".toByteArray()
        val emoji = "😀".toByteArray()
        val first = reader.feed(head + emoji.copyOfRange(0, 2))
        assertEquals(emptyList<AnthropicSseEvent>(), first)
        assertFalse(reader.isFailed)
        val second = reader.feed(emoji.copyOfRange(2, 4) + "\n\n".toByteArray())
        assertEquals(listOf(AnthropicSseEvent("message", "x😀")), second)
    }

    @Test
    fun incompleteTailWaitsForMoreBytes() {
        // é is 2 bytes: C3 A9 — the raw first byte is buffered across chunks.
        val reader = AnthropicSseReader()
        val first = reader.feed("data: e".toByteArray() + byteArrayOf(0xC3.toByte()))
        assertEquals(emptyList<AnthropicSseEvent>(), first)
        assertFalse(reader.isFailed)
        val second = reader.feed(byteArrayOf(0xA9.toByte(), 0x0A.toByte(), 0x0A.toByte()))
        assertEquals(listOf(AnthropicSseEvent("message", "eé")), second)
    }

    @Test
    fun malformedUtf8Fails() {
        val reader = AnthropicSseReader()
        val bytes =
            byteArrayOf(0x44.toByte(), 0xFF.toByte(), 0x0A.toByte(), 0x0A.toByte())
        assertEquals(emptyList<AnthropicSseEvent>(), reader.feed(bytes))
        assertTrue(reader.isFailed)
        assertNotNull(reader.failure)
        assertTrue(reader.failure!!.contains("utf8"))
        assertEquals(emptyList<AnthropicSseEvent>(), reader.finish())
    }

    @Test
    fun oversizeLineFails() {
        val line = "d".repeat(1_048_577)
        val reader = AnthropicSseReader()
        reader.feed(line.toByteArray())
        assertTrue(reader.isFailed)
        assertTrue(reader.failure!!.contains("too long"))
    }

    @Test
    fun pendingEventWithoutBlankLineDispatchedOnFinish() {
        // The lines are complete (newline-terminated) but the final blank line
        // that closes the event is missing: the lenient tail dispatches it.
        val reader = AnthropicSseReader()
        assertEquals(
            0,
            reader.feed("event: message_stop\ndata: {\"type\":\"message_stop\"}\n".toByteArray()).size,
        )
        assertEquals(
            listOf(AnthropicSseEvent("message_stop", "{\"type\":\"message_stop\"}")),
            reader.finish(),
        )
    }

    @Test
    fun truncatedPartialLineDiscarded() {
        // An unterminated partial line at EOF is discarded per spec; only the
        // complete events before it are delivered.
        val reader = AnthropicSseReader()
        val out =
            reader.feed("data: a\n\ndata: cut".toByteArray()) + reader.finish()
        assertEquals(listOf(AnthropicSseEvent("message", "a")), out)
    }

    @Test
    fun idAndRetryFieldsIgnored() {
        assertEquals(
            listOf(AnthropicSseEvent("message_stop", "a")),
            events("id: 42\nretry: 3000\nevent: message_stop\ndata: a\n\n"),
        )
    }

    @Test
    fun unknownFieldsIgnored() {
        assertEquals(
            listOf(AnthropicSseEvent("message_stop", "a")),
            events("foo: bar\nevent: message_stop\ndata: a\n\n"),
        )
    }
}

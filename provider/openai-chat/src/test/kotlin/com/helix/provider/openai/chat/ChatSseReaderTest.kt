package com.helix.provider.openai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSseReaderTest {
    private fun payloads(vararg chunks: String): List<String> {
        val reader = ChatSseReader()
        val out = ArrayList<String>()
        chunks.forEach { out += reader.feed(it.toByteArray()) }
        out += reader.finish()
        return out
    }

    @Test
    fun simpleDataEvent() {
        assertEquals(listOf("hello"), payloads("data: hello\n\n"))
    }

    @Test
    fun multiLineDataJoinsWithLf() {
        assertEquals(listOf("a\nb"), payloads("data: a\ndata: b\n\n"))
    }

    @Test
    fun crlfAndBareCrLineEndings() {
        assertEquals(listOf("x"), payloads("data: x\r\n\r\n"))
        assertEquals(listOf("y"), payloads("data: y\r\r"))
    }

    @Test
    fun commentLinesAreIgnored() {
        // The vendor pings the stream with comment lines (refusal policy notice).
        assertEquals(
            listOf("x"),
            payloads(": OPENAI:HTTP_REFUSAL_POLICY_NOTICE\n: ping\ndata: x\n\n"),
        )
    }

    @Test
    fun bareDataFieldYieldsNoPayload() {
        assertEquals(emptyList<String>(), payloads("data\n\n"))
        // `data:value` (no space) is a legal data line with an unstripped value.
        assertEquals(listOf("value"), payloads("data:value\n\n"))
    }

    @Test
    fun multipleEventsInOneChunk() {
        assertEquals(listOf("a", "b", "c"), payloads("data: a\n\ndata: b\n\ndata: c\n\n"))
    }

    @Test
    fun oneByteChunkingMatchesWholeStream() {
        val stream = "data: one\n\ndata: two\n\n"
        val whole = payloads(stream)
        val reader = ChatSseReader()
        val out = ArrayList<String>()
        stream.toByteArray().forEach { byte -> out += reader.feed(byteArrayOf(byte)) }
        out += reader.finish()
        assertEquals(whole, out)
    }

    @Test
    fun multiByteUtf8SplitAcrossChunks() {
        // 😀 is 4 bytes: F0 9F 98 80 — split 2+2 inside the payload.
        val emoji = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())
        val prefix = "data: a".toByteArray()
        val suffix = "\n\n".toByteArray()
        val reader = ChatSseReader()
        val out = ArrayList<String>()
        out += reader.feed(prefix + emoji.copyOfRange(0, 2))
        out += reader.feed(emoji.copyOfRange(2, 4) + suffix)
        out += reader.finish()
        assertEquals(listOf("a😀"), out)
        assertFalse(reader.isFailed)
    }

    @Test
    fun incompleteTailWaitsForMoreBytes() {
        // é is 2 bytes: C3 A9 — split 1+1 at the chunk boundary (raw bytes,
        // not an encoded character: the first chunk ends mid-sequence).
        val reader = ChatSseReader()
        val first = reader.feed("data: e".toByteArray() + byteArrayOf(0xC3.toByte()))
        assertEquals(emptyList<String>(), first)
        assertFalse(reader.isFailed)
        val second = reader.feed(byteArrayOf(0xA9.toByte(), 0x0A, 0x0A))
        assertEquals(listOf("eé"), second)
    }

    @Test
    fun malformedUtf8ByteFails() {
        val reader = ChatSseReader()
        reader.feed("data: bad".toByteArray() + byteArrayOf(0xFF.toByte()))
        assertTrue(reader.isFailed)
        assertTrue(reader.feed("more".toByteArray()).isEmpty())
        assertTrue(reader.finish().isEmpty())
    }

    @Test
    fun oversizeLineFails() {
        val reader = ChatSseReader()
        reader.feed(("data: " + "x".repeat(1_048_576) + "\n\n").toByteArray())
        assertTrue(reader.isFailed)
        assertTrue(reader.finish().isEmpty())
    }

    @Test
    fun terminatedDataWithoutBlankLineIsDispatchedOnFinish() {
        // A well-formed event that omits the final blank line: lenient tail.
        val reader = ChatSseReader()
        assertEquals(emptyList<String>(), reader.feed("data: x\n".toByteArray()))
        assertEquals(listOf("x"), reader.finish())
    }

    @Test
    fun truncatedPartialLineIsDiscarded() {
        // A line never terminated by a line break is discarded per the SSE spec.
        val reader = ChatSseReader()
        assertEquals(emptyList<String>(), reader.feed("data: x".toByteArray()))
        assertEquals(emptyList<String>(), reader.finish())
        assertFalse(reader.isFailed)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        assertEquals(listOf("x"), payloads("id: 42\nretry: 3000\nevent: noise\ndata: x\n\n"))
    }
}

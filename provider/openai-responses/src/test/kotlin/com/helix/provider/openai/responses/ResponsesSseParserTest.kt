package com.helix.provider.openai.responses

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponsesSseParserTest {
    private fun types(events: List<SseEvent>): List<Pair<String, String>> = events.map { it.type to it.data }

    @Test
    fun parsesSimpleEvent() {
        val parser = ResponsesSseParser()
        val events = parser.feed("event: ping\ndata: {\"a\":1}\n\n".toByteArray())
        assertEquals(listOf("ping" to """{"a":1}"""), types(events))
    }

    @Test
    fun eventTypeDefaultsToMessage() {
        val parser = ResponsesSseParser()
        val events = parser.feed("data: bare\n\n".toByteArray())
        assertEquals(listOf("message" to "bare"), types(events))
    }

    @Test
    fun bareEventFieldYieldsMessageType() {
        // A bare `event` field has an empty value; the spec maps an empty event
        // type to "message".
        val parser = ResponsesSseParser()
        val events = parser.feed("event\ndata: x\n\n".toByteArray())
        assertEquals(listOf("message" to "x"), types(events))
    }

    @Test
    fun multiLineDataJoinsWithLf() {
        val parser = ResponsesSseParser()
        val events = parser.feed("data: l1\ndata: l2\n\n".toByteArray())
        assertEquals(listOf("message" to "l1\nl2"), types(events))
    }

    @Test
    fun crlfAndBareCrTerminateLines() {
        val crlf = ResponsesSseParser().feed("event: a\r\ndata: x\r\n\r\n".toByteArray())
        assertEquals(listOf("a" to "x"), types(crlf))
        val bare = ResponsesSseParser().feed("data: x\r\ndata: y\r\n\r\n".toByteArray())
        assertEquals(listOf("message" to "x\ny"), types(bare))
    }

    @Test
    fun commentsAreIgnored() {
        val parser = ResponsesSseParser()
        val events = parser.feed(": keepalive\nevent: ping\ndata: {}\n\n".toByteArray())
        assertEquals(listOf("ping" to "{}"), types(events))
    }

    @Test
    fun leadingSpaceAfterColonIsStrippedOnce() {
        val parser = ResponsesSseParser()
        val events = parser.feed("data:  double\n\n".toByteArray())
        assertEquals(listOf("message" to " double"), types(events))
    }

    @Test
    fun unknownAndReservedFieldsAreIgnored() {
        val parser = ResponsesSseParser()
        val events = parser.feed("id: 42\nretry: 3000\ncustom: z\ndata: ok\n\n".toByteArray())
        assertEquals(listOf("message" to "ok"), types(events))
    }

    @Test
    fun arbitraryByteChunkingProducesSameEvents() {
        val fixture =
            buildString {
                append("event: response.output_text.delta\n")
                append("data: {\"delta\":\"héllo😀\"}\n\n")
                append("event: ping\ndata: {}\n\n")
            }.toByteArray()
        val oneByte = ResponsesSseParser()
        val single =
            buildList {
                for (b in fixture) addAll(oneByte.feed(byteArrayOf(b)))
                addAll(oneByte.finish())
            }
        val whole = ResponsesSseParser().feed(fixture)
        assertEquals(types(whole), types(single))
    }

    @Test
    fun multiByteUtf8SplitAcrossChunks() {
        // 😀 is F0 9F 98 80; feed it split 2+2, then finish the event.
        val prefix = "data: a".toByteArray()
        val emoji = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())
        val suffix = "\n\n".toByteArray()
        val parser = ResponsesSseParser()
        val out = ArrayList<SseEvent>()
        out += parser.feed(prefix + emoji.copyOfRange(0, 2))
        out += parser.feed(emoji.copyOfRange(2, 4) + suffix)
        assertEquals(listOf("message" to "a😀"), types(out))
    }

    @Test
    fun incompleteUtf8TailWaitsForMoreBytes() {
        // The chunk ends inside a 3-byte sequence (é = C3 A9).
        val parser = ResponsesSseParser()
        val first = parser.feed("data: a".toByteArray() + byteArrayOf(0xC3.toByte()))
        assertEquals(0, first.size)
        val rest = parser.feed(byteArrayOf(0xA9.toByte(), '\n'.code.toByte(), '\n'.code.toByte()))
        assertEquals(listOf("message" to "aé"), types(rest))
    }

    @Test
    fun malformedUtf8FailsParse() {
        val parser = ResponsesSseParser()
        val events = parser.feed(byteArrayOf('d'.code.toByte(), 0xFF.toByte(), '\n'.code.toByte()))
        assertEquals(0, events.size)
        assertEquals(true, parser.isFailed)
        assertEquals(0, parser.feed("data: x\n\n".toByteArray()).size)
    }

    @Test
    fun overlongLineFailsParse() {
        // 1 MiB per-line bound (the parser's MAX_LINE_LENGTH), tripped as soon as
        // the line buffer crosses it — even before the line is terminated.
        val parser = ResponsesSseParser()
        parser.feed("data: ".toByteArray() + "x".repeat(1_048_576).toByteArray())
        assertEquals(true, parser.isFailed)
    }

    @Test
    fun pendingEventWithoutFinalBlankLineIsDispatchedByFinish() {
        val parser = ResponsesSseParser()
        assertEquals(0, parser.feed("event: done\ndata: {\"v\":1}\n".toByteArray()).size)
        assertEquals(listOf("done" to """{"v":1}"""), types(parser.finish()))
    }

    @Test
    fun partialLineWithoutBreakAtEndIsDiscarded() {
        val parser = ResponsesSseParser()
        val events = parser.feed("data: complete\n\ndata: cut".toByteArray())
        assertEquals(listOf("message" to "complete"), types(events))
        assertEquals(0, parser.finish().size)
    }

    @Test
    fun eventWithoutDataIsNotDispatched() {
        val parser = ResponsesSseParser()
        val events = parser.feed("event: only\nevent: other\ndata: real\n\n".toByteArray())
        assertEquals(listOf("other" to "real"), types(events))
    }
}

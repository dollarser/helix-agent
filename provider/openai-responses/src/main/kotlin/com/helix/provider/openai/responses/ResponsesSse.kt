package com.helix.provider.openai.responses

/**
 * Incremental Server-Sent Events parser for the OpenAI Responses stream
 * (doc 02 section 6.2: the SSE parser must handle a line split across chunks,
 * multi-line data and UTF-8 byte boundaries).
 *
 * Contract (WHATWG SSE):
 * - chunks may split anywhere: mid-line, mid-field, or inside a multi-byte UTF-8
 *   sequence (an incomplete trailing sequence is buffered, at most 3 bytes);
 * - a line ends on LF, CRLF or a bare CR;
 * - lines starting with `:` are comments; `field: value` strips exactly one
 *   leading space from the value; a line without `:` is a bare field with an
 *   empty value;
 * - an empty line dispatches the buffered event (the `event` field defaults to
 *   `message`; multiple `data` lines join with LF);
 * - [finish] dispatches a pending event that still carries data when the stream
 *   ends (lenient tail: a well-formed event that omits the final blank line is
 *   still delivered; a genuinely truncated line is discarded per spec and the
 *   decoder's terminal guard turns the missing termination into a retryable
 *   `Error(PROTOCOL)`), while a terminated-but-malformed JSON payload is
 *   rejected downstream by the decoder as a non-retryable protocol failure;
 * - malformed UTF-8, an over-long line or an over-large event marks the parse
 *   failed; [failure] carries a diagnostic reason and [feed]/[finish] stop
 *   producing events (the decoder maps the failure to `Error(PROTOCOL)`).
 *
 * The parser is deliberately synchronous: the transport layer (HXA-025) feeds
 * raw HTTP body chunks; no HTTP or coroutine types leak in.
 */
internal class ResponsesSseParser {
    private val utf8Tail = ArrayList<Byte>(3)
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var pending = ArrayList<SseEvent>()
    private var eventType: String? = null
    private var failed = false
    private var failureReason: String? = null

    /** Diagnostic reason once the parse failed (never a vendor secret). */
    val failure: String?
        get() = failureReason

    /** True once a parse-level failure happened. */
    val isFailed: Boolean
        get() = failed

    /** Feed raw bytes; returns the complete SSE events they completed. */
    fun feed(chunk: ByteArray): List<SseEvent> {
        if (!failed) {
            if (utf8Tail.isEmpty()) {
                decodeInto(chunk)
            } else {
                val merged = ByteArray(utf8Tail.size + chunk.size)
                for (i in utf8Tail.indices) merged[i] = utf8Tail[i]
                chunk.copyInto(merged, utf8Tail.size)
                utf8Tail.clear()
                decodeInto(merged)
            }
        }
        if (failed) return emptyList()
        val out = pending
        pending = ArrayList()
        return out
    }

    /** Stream end: dispatch a pending event with data, if any. */
    fun finish(): List<SseEvent> {
        if (!failed && data.isNotEmpty()) dispatch()
        if (failed) return emptyList()
        val out = pending
        pending = ArrayList()
        return out
    }

    private fun decodeInto(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size && !failed) {
            val step = decodeStep(bytes, i)
            if (step == null) break
            appendCodePoint(step.cp)
            i += step.consumed
        }
        if (!failed) processLineBreaks()
    }

    /**
     * Decode exactly one UTF-8 sequence at [i]. Returns null when the parse
     * cannot proceed (incomplete sequence buffered in [utf8Tail], or failure).
     */
    private fun decodeStep(
        bytes: ByteArray,
        i: Int,
    ): Utf8Step? {
        val first = bytes[i].toInt() and 0xFF
        val length = sequenceLength(first)
        val complete = length in 1..4 && i + length <= bytes.size
        if (!complete) {
            if (length == 0) {
                fail("malformed utf8 leading byte")
            } else {
                for (b in i until bytes.size) utf8Tail += bytes[b]
            }
            return null
        }
        val cp = assembleCodePoint(bytes, i, length)
        return if (cp >= minCodePoint(length) && cp <= MAX_CODE_POINT) {
            Utf8Step(cp, length)
        } else {
            fail("malformed utf8 sequence")
            null
        }
    }

    /** Assembled code point of the sequence at [i], or -1 for a bad continuation byte. */
    private fun assembleCodePoint(
        bytes: ByteArray,
        i: Int,
        length: Int,
    ): Int {
        val first = bytes[i].toInt() and 0xFF
        var cp =
            when (length) {
                2 -> first and 0x1F
                3 -> first and 0x0F
                4 -> first and 0x07
                else -> first
            }
        for (j in 1 until length) {
            val b = bytes[i + j].toInt() and 0xFF
            if (b and 0xC0 != 0x80) return -1
            cp = (cp shl 6) or (b and 0x3F)
        }
        return cp
    }

    private fun appendCodePoint(cp: Int) {
        if (line.length >= MAX_LINE_LENGTH) {
            // Trip immediately: an unterminated line must not grow unbounded.
            fail("sse line too long")
            return
        }
        line.append(codePointChars(cp))
    }

    private fun processLineBreaks() {
        while (!failed) {
            val nl = line.indexOf('\n')
            val cr = line.indexOf('\r')
            val breakPos =
                when {
                    nl >= 0 && (cr < 0 || nl < cr) -> nl
                    cr >= 0 -> cr
                    else -> return
                }
            var end = breakPos + 1
            if (line[breakPos] == '\r' && end < line.length && line[end] == '\n') end++
            val text = line.substring(0, breakPos)
            line.delete(0, end)
            processLine(text)
        }
    }

    private fun processLine(raw: String) {
        if (failed) return
        if (raw.length > MAX_LINE_LENGTH) {
            fail("sse line too long")
            return
        }
        when {
            raw.isEmpty() -> {
                if (data.isNotEmpty()) dispatch()
            }

            raw.startsWith(":") -> {
                Unit
            }

            // comment

            else -> {
                val colon = raw.indexOf(':')
                val field = if (colon < 0) raw else raw.substring(0, colon)
                val value =
                    if (colon < 0) {
                        ""
                    } else {
                        val v = raw.substring(colon + 1)
                        if (v.startsWith(" ")) v.substring(1) else v
                    }
                // `id`/`retry` and unknown fields are accepted and ignored per spec.
                when (field) {
                    "data" -> {
                        data.append(value).append('\n')
                        if (data.length > MAX_EVENT_DATA_LENGTH) fail("sse event data too large")
                    }

                    "event" -> {
                        eventType = value
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }

    private fun dispatch() {
        // An empty event type (e.g. a bare `event` field) maps to "message" per spec.
        val type = eventType?.takeIf { it.isNotEmpty() } ?: "message"
        val payload = data.substring(0, data.length - 1) // drop the trailing LF
        eventType = null
        data.setLength(0)
        if (payload.isNotEmpty()) pending += SseEvent(type, payload)
    }

    private fun fail(reason: String) {
        failed = true
        failureReason = reason
    }

    private companion object {
        const val MAX_LINE_LENGTH = 1_048_576 // 1 MiB per line
        const val MAX_EVENT_DATA_LENGTH = 8_388_608 // 8 MiB per event payload
        const val MAX_CODE_POINT = 0x10FFFF

        fun sequenceLength(leading: Int): Int =
            when {
                leading and 0x80 == 0 -> 1
                leading and 0xE0 == 0xC0 -> 2
                leading and 0xF0 == 0xE0 -> 3
                leading and 0xF8 == 0xF0 -> 4
                else -> 0
            }

        fun minCodePoint(length: Int): Int =
            when (length) {
                2 -> 0x80
                3 -> 0x800
                4 -> 0x1_0000
                else -> 0
            }

        fun codePointChars(cp: Int): String = String(Character.toChars(cp))
    }

    private class Utf8Step(
        val cp: Int,
        val consumed: Int,
    )
}

/** One complete SSE event: [type] (the `event:` field or `message`) and [data] (joined `data:` fields). */
internal data class SseEvent(
    val type: String,
    val data: String,
)

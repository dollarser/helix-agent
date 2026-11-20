package com.helix.tools.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFetchResponseParserTest {
    @Test
    fun chunkFramingDoesNotConsumeBodyBudget() {
        val result = parse("Transfer-Encoding: chunked", "2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n", 4)
        assertEquals("abcd", result.body)
        assertEquals(4L, result.bodyBytes)
        assertFalse(result.truncated)
    }

    @Test
    fun chunkedOverflowReportsTruncationAndObservedLowerBound() {
        val result = parse("Transfer-Encoding: chunked", "6\r\nabcdef\r\n0\r\n\r\n", 4)
        assertEquals("abcd", result.body)
        assertEquals(5L, result.bodyBytes)
        assertTrue(result.truncated)
    }

    @Test
    fun nextChunkBeyondExactCapIsTruncated() {
        val result = parse("Transfer-Encoding: chunked", "4\r\nabcd\r\n1\r\ne\r\n0\r\n\r\n", 4)
        assertTrue(result.truncated)
        assertEquals("abcd", result.body)
    }

    @Test
    fun fixedLengthShortReadIsNotPaddedWithZeroes() {
        assertThrows(Exception::class.java) { parse("Content-Length: 4", "ab", 4) }
    }

    @Test
    fun fixedLengthLargerThanCapRemainsBounded() {
        val result = parse("Content-Length: 100", "abcd", 4)
        assertEquals("abcd", result.body)
        assertEquals(100L, result.bodyBytes)
        assertTrue(result.truncated)
    }

    @Test
    fun closeDelimitedExactCapIsComplete() {
        assertFalse(parse("", "abcd", 4).truncated)
        assertTrue(parse("", "abcde", 4).truncated)
    }

    @Test
    fun malformedOrIncompleteChunksFailRatherThanReturningPartialSuccess() {
        listOf("x\r\n", "-1\r\n", "4\r\nab", "2\r\nabZZ", "2\r\nab\r\n", "0\r\n").forEach {
            assertThrows(Exception::class.java) { parse("Transfer-Encoding: chunked", it, 10) }
        }
    }

    @Test
    fun chunkExtensionsAndTrailersAreHandled() {
        val result = parse("Transfer-Encoding: chunked", "2;foo=bar\r\nab\r\n0\r\nX-Test: yes\r\n\r\n", 10)
        assertEquals("ab", result.body)
        assertFalse(result.truncated)
    }

    private fun parse(
        headers: String,
        body: String,
        cap: Long,
    ): HttpFetchResponseParser.ParsedResponse {
        val headerBlock = if (headers.isEmpty()) "" else "$headers\r\n"
        return HttpFetchResponseParser.read(
            "HTTP/1.1 200 OK\r\n${headerBlock}\r\n$body".byteInputStream(),
            HttpFetchRequest("https://example.com", "GET", cap, Long.MAX_VALUE),
        )
    }
}

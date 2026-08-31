package com.helix.provider.api

import com.helix.provider.api.wire.WireRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireRequestTest {
    private fun validRequest(method: String = "POST"): WireRequest =
        WireRequest(
            method,
            "https://example.test:443/v1/chat",
            mapOf("Authorization" to "Bearer tok"),
            if (method == "GET") null else "body".toByteArray(),
        )

    @Test
    fun postWithBodyAndHeadersIsAccepted() {
        val request = validRequest()
        assertEquals("POST", request.method)
        assertEquals(1, request.headers.size)
    }

    @Test
    fun getWithoutBodyIsAccepted() {
        validRequest("GET")
    }

    @Test
    fun headWithoutBodyIsAccepted() {
        validRequest("HEAD")
    }

    @Test
    fun unknownMethodIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { validRequest("PATCH") }
        assertThrows(IllegalArgumentException::class.java) { validRequest("post") }
    }

    @Test
    fun getWithBodyIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            WireRequest("GET", "https://example.test", emptyMap(), "x".toByteArray())
        }
    }

    @Test
    fun blankUrlIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { WireRequest("POST", "  ", emptyMap(), null) }
    }

    @Test
    fun controlCharacterInUrlIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { WireRequest("POST", "https://a\nb", emptyMap(), null) }
    }

    @Test
    fun blankHeaderNameIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { WireRequest("POST", "https://a.test", mapOf("" to "v"), null) }
    }

    @Test
    fun controlCharacterInHeaderValueIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { WireRequest("POST", "https://a.test", mapOf("X" to "v\u0001"), null) }
    }

    @Test
    fun duplicateHeaderNamesAreRejectedCaseInsensitively() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) {
            WireRequest(
                "POST",
                "https://a.test",
                mapOf("X-Api-Key" to "a", "x-api-key" to "b"),
                null,
            )
        }
    }
}
